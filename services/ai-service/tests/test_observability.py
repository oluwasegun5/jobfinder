import json
import logging
import uuid
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from opentelemetry.sdk.trace.export import SpanExporter, SpanExportResult

from app.config import Settings
from app.llm import FakeProvider
from app.llm.base import LLMProviderError, LLMRequest, LLMUsage, ModelTier
from app.main import create_app
from app.observability.logs import JsonFormatter, configure_logging
from app.observability.metrics import Metrics, ObservedProvider
from app.observability.redaction import MAX_LENGTH, RedactingFilter, redact
from app.observability.sentry import scrub_event
from app.security import SERVICE_TOKEN_HEADER
from tests.conftest import TEST_TOKEN

TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"


class MemoryExporter(SpanExporter):
    def __init__(self) -> None:
        self.spans: list[object] = []

    def export(self, spans):  # type: ignore[no-untyped-def]
        self.spans.extend(spans)
        return SpanExportResult.SUCCESS


@pytest.fixture
def exporter() -> MemoryExporter:
    return MemoryExporter()


@pytest.fixture
def traced_client(
    settings: Settings, fake_provider: FakeProvider, exporter: MemoryExporter
) -> Iterator[TestClient]:
    app = create_app(settings, provider=fake_provider, span_exporter=exporter)
    with TestClient(app, headers={SERVICE_TOKEN_HEADER: TEST_TOKEN}) as c:
        yield c


# --- redaction ---


@pytest.mark.parametrize(
    "text",
    [
        "failed for ada@example.co.uk",
        "Authorization: Bearer abc.DEF-123_xyz",
        "key sk-ant-api03-AbCdEfGh1234567890 used",
        "stripe sk_live_51HabcdefgHIJK",
        "password=hunter2hunter2",
        '{"api_key": "hunter2hunter2"}',
        "X-Service-Token: hunter2hunter2",
        "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl",
        "call +234 803 123 4567",
    ],
)
def test_redact_masks_what_is_recognisable(text: str) -> None:
    out = redact(text)
    for secret in ("ada@", "abc.DEF", "AbCdEfGh", "51Habc", "hunter2", "eyJhbGci", "803 123"):
        assert secret not in out


def test_redact_leaves_ordinary_text_and_caps_length() -> None:
    plain = "run 7d9f3c1e-4b2a-4c1d-9e8f-0a1b2c3d4e5f ok at 2026-10-07T06:50:12Z, token budget 4096"
    assert redact(plain) == plain
    assert redact("cv " * 5000).endswith("[truncated]")
    assert len(redact("cv " * 5000)) < MAX_LENGTH + 50
    assert redact("") == ""


def test_filter_redacts_message_arguments_too() -> None:
    record = logging.LogRecord(
        "t",
        logging.INFO,
        __file__,
        1,
        "login %s with %s",
        ("ada@example.test", "Bearer abc.def"),
        None,
    )
    assert RedactingFilter().filter(record)
    assert "ada@" not in record.getMessage()
    assert "abc.def" not in record.getMessage()


def test_json_logs_carry_the_trace_id_and_no_pii(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    json_settings = settings.model_copy(update={"log_format": "json"})
    configure_logging(json_settings)
    from opentelemetry.sdk.trace import TracerProvider

    tracer = TracerProvider().get_tracer("test")
    with tracer.start_as_current_span("work") as span:
        trace_id = format(span.get_span_context().trace_id, "032x")
        logging.getLogger("x").warning("sign-in failed for ada@example.test")
        try:
            raise ValueError("duplicate ada@example.test")
        except ValueError:
            logging.getLogger("x").exception("boom")
    lines = [json.loads(line) for line in capsys.readouterr().out.strip().splitlines()]
    assert lines[0]["traceId"] == trace_id
    assert lines[0]["message"] == "sign-in failed for [email]"
    assert "ada@" not in json.dumps(lines)
    configure_logging(settings)  # back to text for the other tests


def test_json_formatter_has_no_trace_outside_a_span() -> None:
    record = logging.LogRecord("t", logging.INFO, __file__, 1, "hello", None, None)
    entry = json.loads(JsonFormatter().format(record))
    assert "traceId" not in entry
    assert entry["service.name"] == "ai-service"


# --- sentry ---


def test_sentry_scrubber_removes_everything_personal() -> None:
    event = {
        "request": {"url": "http://x/?email=ada@example.test", "data": "my CV"},
        "user": {"email": "ada@example.test"},
        "server_name": "laptop",
        "breadcrumbs": {"values": [{"message": "ada@example.test"}]},
        "extra": {"cv": "my CV"},
        "logentry": {"message": "failed for ada@example.test", "params": ["ada@example.test"]},
        "exception": {
            "values": [
                {
                    "type": "ValueError",
                    "value": "bad ada@example.test",
                    "stacktrace": {"frames": [{"function": "f", "vars": {"cv": "my CV"}}]},
                }
            ]
        },
    }
    out = scrub_event(event, {})
    assert "request" not in out and "user" not in out and "extra" not in out
    assert "breadcrumbs" not in out and "server_name" not in out
    assert "ada@" not in json.dumps(out)
    assert "vars" not in out["exception"]["values"][0]["stacktrace"]["frames"][0]


# --- metrics ---


def test_metrics_need_the_service_token(anon_client: TestClient) -> None:
    assert anon_client.get("/metrics").status_code == 401
    assert anon_client.get("/metrics", headers={SERVICE_TOKEN_HEADER: "nope"}).status_code == 401


def test_metrics_count_provider_calls_and_requests(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue('{"status": "ok"}')
    client.post("/v1/diagnostics/llm", json={"user_id": str(uuid.uuid4())})

    body = client.get("/metrics").text

    calls = (
        'ai_llm_calls_total{feature="diagnostics",model="fake-fast",outcome="ok",provider="fake"}'
    )
    assert calls + " 1.0" in body
    assert 'ai_llm_tokens_total{direction="input",feature="diagnostics",model="fake-fast"}' in body
    route = 'method="POST",route="/v1/diagnostics/llm",status="200"'
    assert "ai_http_request_duration_seconds_count{" + route + "} 1.0" in body
    assert "python_gc_objects_collected_total" in body


async def test_observed_provider_counts_cost_and_failures() -> None:
    from decimal import Decimal

    metrics = Metrics()
    user = uuid.uuid4()

    class Failing:
        name = "boom"

        async def generate(self, request: LLMRequest):  # type: ignore[no-untyped-def]
            error = LLMProviderError("down", retryable=True)
            error.usage = [
                LLMUsage(
                    user_id=user,
                    feature="f",
                    provider="boom",
                    model="m",
                    input_tokens=10,
                    output_tokens=0,
                    cost_usd=Decimal("0.5"),
                    latency_ms=200,
                    prompt_version="f/v1",
                )
            ]
            raise error

        async def aclose(self) -> None:
            return None

    observed = ObservedProvider(Failing(), metrics)
    request = LLMRequest(user, "f", "f/v1", ModelTier.FAST, "s", "u")
    with pytest.raises(LLMProviderError):
        await observed.generate(request)

    assert metrics.llm_calls.labels("f", "boom", "m", "error")._value.get() == 1.0
    assert metrics.llm_cost.labels("f", "m")._value.get() == 0.5


# --- tracing ---


def test_an_incoming_trace_continues_in_a_span(
    traced_client: TestClient, exporter: MemoryExporter
) -> None:
    traced_client.post(
        "/v1/diagnostics/llm",
        json={"user_id": str(uuid.uuid4())},
        headers={"traceparent": f"00-{TRACE_ID}-00f067aa0ba902b7-01"},
    )
    trace_ids = {format(s.get_span_context().trace_id, "032x") for s in exporter.spans}  # type: ignore[attr-defined]
    assert TRACE_ID in trace_ids


def test_probes_and_scrapes_make_no_spans(
    traced_client: TestClient, exporter: MemoryExporter
) -> None:
    traced_client.get("/health")
    traced_client.get("/metrics")
    assert exporter.spans == []
