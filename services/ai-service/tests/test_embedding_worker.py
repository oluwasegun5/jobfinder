import json
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import httpx
import pytest
from pydantic import SecretStr

from app.config import EmbeddingProviderName, Settings
from app.embeddings import (
    EmbeddingBatch,
    EmbeddingError,
    EmbeddingInputType,
    FakeEmbeddingProvider,
)
from app.embeddings.core_client import CoreApiClient, EmbeddingKind, Inputs
from app.embeddings.worker import EmbeddingWorker, parse_message
from app.security import SERVICE_TOKEN_HEADER

TOKEN = "test-service-token-0123456789abcdef"
DIM = 8
MODEL = "fake-embed-8"


def _settings(**overrides: Any) -> Settings:
    base: dict[str, Any] = {
        "ai_service_token": SecretStr(TOKEN),
        "rabbitmq_enabled": False,
        "embedding_provider": EmbeddingProviderName.FAKE,
        "embedding_model": MODEL,
        "embedding_dimension": DIM,
        "embedding_max_attempts": 3,
    }
    return Settings(**{**base, **overrides})


class FakeCore:
    """core-api's internal endpoints, as far as the worker can tell."""

    def __init__(
        self, *, model: str = MODEL, dimension: int = DIM, input_type: str = "document"
    ) -> None:
        self.model = model
        self.dimension = dimension
        self.input_type = input_type
        self.texts: dict[UUID, str] = {}
        self.owners: dict[UUID, UUID] = {}
        self.up_to_date: set[UUID] = set()
        self.failures: list[int] = []  # statuses to answer with before behaving
        self.input_requests: list[dict[str, Any]] = []
        self.results_requests: list[dict[str, Any]] = []
        self.tokens_seen: list[str] = []

    def add(self, text: str, *, owner: UUID | None = None) -> UUID:
        item_id = uuid4()
        self.texts[item_id] = text
        if owner is not None:
            self.owners[item_id] = owner
        return item_id

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.tokens_seen.append(request.headers.get(SERVICE_TOKEN_HEADER, ""))
        if self.failures:
            return httpx.Response(self.failures.pop(0), json={"title": "boom"})
        body = json.loads(request.content)
        if request.url.path.endswith("/inputs"):
            self.input_requests.append(body)
            items, skipped = [], []
            for raw in body["ids"]:
                item_id = UUID(raw)
                if item_id in self.up_to_date:
                    skipped.append({"id": raw, "reason": "UP_TO_DATE"})
                elif item_id not in self.texts:
                    skipped.append({"id": raw, "reason": "NOT_FOUND"})
                else:
                    items.append(
                        {
                            "id": raw,
                            "userId": str(self.owners[item_id]) if item_id in self.owners else None,
                            "text": self.texts[item_id],
                            "inputHash": f"hash-of-{self.texts[item_id]}".ljust(64, "0")[:64],
                        }
                    )
            return httpx.Response(
                200,
                json={
                    "model": self.model,
                    "dimension": self.dimension,
                    "inputType": self.input_type,
                    "items": items,
                    "skipped": skipped,
                },
            )
        self.results_requests.append(body)
        return httpx.Response(200, json={"applied": len(body["items"]), "stale": 0, "missing": 0})

    def client(self) -> CoreApiClient:
        http = httpx.AsyncClient(
            base_url="http://core.test",
            transport=httpx.MockTransport(self.handle),
            headers={SERVICE_TOKEN_HEADER: TOKEN},
        )
        return CoreApiClient(_settings(), http)


class _Delivery:
    def __init__(self, body: bytes) -> None:
        self.body = body
        self.state = "open"

    async def ack(self) -> None:
        self.state = "acked"

    async def reject(self, requeue: bool = False) -> None:
        assert not requeue
        self.state = "rejected"


def _message(item_id: UUID) -> _Delivery:
    return _Delivery(json.dumps({"id": str(item_id)}).encode())


async def _no_sleep(seconds: float) -> None:
    return None


def _worker(core: FakeCore, provider: Any = None, **settings: Any) -> tuple[EmbeddingWorker, Any]:
    provider = provider or FakeEmbeddingProvider(model=MODEL, dimension=DIM)
    return EmbeddingWorker(
        _settings(**settings), provider, core.client(), sleep=_no_sleep
    ), provider


def test_message_parsing() -> None:
    item_id = uuid4()
    assert parse_message(json.dumps({"id": str(item_id)}).encode()) == item_id
    for bad in (b"", b"[]", b"{}", b'{"id": 5}', b'{"id": "not-a-uuid"}', b"\xff"):
        assert parse_message(bad) is None


async def test_a_batch_of_jobs_is_embedded_in_one_call_and_stored_with_its_hashes() -> None:
    core = FakeCore()
    ids = [core.add(f"job {n}") for n in range(3)]
    worker, provider = _worker(core)
    messages = [_message(i) for i in ids]

    await worker.handle_messages(EmbeddingKind.JOB, messages)

    assert [m.state for m in messages] == ["acked"] * 3
    assert len(provider.calls) == 1
    assert provider.calls[0] == ([f"job {n}" for n in range(3)], EmbeddingInputType.DOCUMENT)
    (stored,) = core.results_requests
    assert stored["kind"] == "JOB"
    assert stored["model"] == MODEL
    assert stored["dimension"] == DIM
    assert [i["id"] for i in stored["items"]] == [str(i) for i in ids]
    assert set(stored["items"][0]) == {"id", "inputHash", "embedding"}
    assert len(stored["items"][0]["embedding"]) == DIM
    (usage,) = stored["usage"]
    assert usage["userId"] is None
    assert usage["feature"] == "embed_job"
    assert usage["provider"] == "fake"
    assert set(usage) == {
        "userId",
        "feature",
        "provider",
        "model",
        "inputTokens",
        "costUsd",
        "latencyMs",
    }
    assert set(core.tokens_seen) == {TOKEN}


async def test_resumes_use_the_query_input_type_and_usage_is_shared_out_per_owner() -> None:
    core = FakeCore(input_type="query")
    owner_a, owner_b = uuid4(), uuid4()
    a = core.add("one two three four", owner=owner_a)
    b = core.add("five six", owner=owner_b)
    worker, provider = _worker(core)

    await worker.handle_messages(EmbeddingKind.RESUME_VERSION, [_message(a), _message(b)])

    assert provider.calls[0][1] is EmbeddingInputType.QUERY
    usage = core.results_requests[0]["usage"]
    assert [u["userId"] for u in usage] == [str(owner_a), str(owner_b)]
    assert [u["feature"] for u in usage] == ["embed_resume"] * 2
    assert sum(u["inputTokens"] for u in usage) == 6


async def test_nothing_is_embedded_for_rows_that_are_already_current_and_the_message_is_acked() -> (
    None
):
    core = FakeCore()
    current = core.add("same text")
    core.up_to_date.add(current)
    worker, provider = _worker(core)
    message = _message(current)

    await worker.handle_messages(EmbeddingKind.JOB, [message])

    assert message.state == "acked"
    assert provider.calls == []
    assert core.results_requests == []


async def test_a_redelivered_duplicate_is_embedded_once_and_both_copies_are_acked() -> None:
    core = FakeCore()
    item = core.add("job")
    worker, provider = _worker(core)
    first, second = _message(item), _message(item)

    await worker.handle_messages(EmbeddingKind.JOB, [first, second])

    assert first.state == second.state == "acked"
    assert core.input_requests[0]["ids"] == [str(item)]
    assert len(provider.calls[0][0]) == 1


async def test_a_malformed_message_is_rejected_and_its_neighbours_still_embedded() -> None:
    core = FakeCore()
    good = core.add("job")
    worker, _ = _worker(core)
    bad = _Delivery(b"not json")
    ok = _message(good)

    await worker.handle_messages(EmbeddingKind.JOB, [bad, ok])

    assert bad.state == "rejected"
    assert ok.state == "acked"


async def test_a_transient_core_api_failure_is_retried_then_succeeds() -> None:
    core = FakeCore()
    core.failures = [503, 429]
    item = core.add("job")
    worker, _ = _worker(core)
    message = _message(item)

    await worker.handle_messages(EmbeddingKind.JOB, [message])

    assert message.state == "acked"
    assert len(core.results_requests) == 1


async def test_a_transient_failure_that_never_ends_dead_letters_the_batch_after_the_attempts() -> (
    None
):
    core = FakeCore()
    core.failures = [503] * 10
    a, b = core.add("a"), core.add("b")
    worker, provider = _worker(core, embedding_max_attempts=3)
    messages = [_message(a), _message(b)]

    await worker.handle_messages(EmbeddingKind.JOB, messages)

    assert [m.state for m in messages] == ["rejected", "rejected"]
    assert provider.calls == []
    assert len(core.failures) == 7  # exactly three attempts were made


async def test_a_permanent_core_api_answer_is_not_retried() -> None:
    core = FakeCore()
    core.failures = [409, 409, 409]
    item = core.add("job")
    worker, _ = _worker(core)
    message = _message(item)

    await worker.handle_messages(EmbeddingKind.JOB, [message])

    assert message.state == "rejected"
    assert len(core.failures) == 2


async def test_a_different_pinned_model_is_refused_loudly_without_calling_the_provider() -> None:
    core = FakeCore(model="voyage-4", dimension=1024)
    item = core.add("job")
    worker, provider = _worker(core)
    message = _message(item)

    await worker.handle_messages(EmbeddingKind.JOB, [message])

    assert message.state == "rejected"
    assert provider.calls == []


class _PoisonedProvider:
    """Fails permanently for any batch that contains the poison text."""

    name = "fake"
    model = MODEL
    dimension = DIM

    def __init__(self) -> None:
        self.inner = FakeEmbeddingProvider(model=MODEL, dimension=DIM)
        self.calls = 0

    async def embed(self, texts: Any, input_type: EmbeddingInputType) -> EmbeddingBatch:
        self.calls += 1
        if "poison" in texts:
            raise EmbeddingError("provider rejected the input", retryable=False)
        return await self.inner.embed(texts, input_type)

    async def aclose(self) -> None:
        return None


async def test_one_bad_row_does_not_take_its_batch_to_the_dead_letter_queue() -> None:
    core = FakeCore()
    good_a, bad, good_b = core.add("fine a"), core.add("poison"), core.add("fine b")
    provider = _PoisonedProvider()
    worker, _ = _worker(core, provider)
    messages = [_message(good_a), _message(bad), _message(good_b)]

    await worker.handle_messages(EmbeddingKind.JOB, messages)

    assert [m.state for m in messages] == ["acked", "rejected", "acked"]
    assert provider.calls == 1 + 3  # the batch, then each row on its own
    stored_ids = {i["id"] for r in core.results_requests for i in r["items"]}
    assert stored_ids == {str(good_a), str(good_b)}


async def test_the_worker_never_raises_even_on_a_bug() -> None:
    core = FakeCore()
    item = core.add("job")

    class Broken:
        name = "fake"
        model = MODEL
        dimension = DIM

        async def embed(self, texts: Any, input_type: EmbeddingInputType) -> EmbeddingBatch:
            raise RuntimeError("bug")

        async def aclose(self) -> None:
            return None

    worker, _ = _worker(core, Broken())
    message = _message(item)

    await worker.handle_messages(EmbeddingKind.JOB, [message])

    assert message.state == "rejected"


@pytest.mark.parametrize("kind", [EmbeddingKind.JOB, EmbeddingKind.RESUME_VERSION])
async def test_the_handler_for_a_kind_is_what_the_batch_collector_calls(
    kind: EmbeddingKind,
) -> None:
    core = FakeCore()
    item = core.add("text", owner=uuid4())
    worker, _ = _worker(core)
    message = _message(item)

    await worker.handler(kind)([message])

    assert message.state == "acked"
    assert core.results_requests[0]["kind"] == kind.value


CONTRACT = (
    Path(__file__).resolve().parents[2]
    / "core-api/src/test/resources/ai-service/embeddings-inputs-ok.json"
)


def test_the_inputs_response_core_api_pins_parses_into_our_model() -> None:
    """core-api's EmbeddingJobTests assert its real /inputs response has exactly this shape."""
    inputs = Inputs.model_validate_json(CONTRACT.read_text(encoding="utf-8"))

    assert inputs.model == "voyage-4"
    assert inputs.dimension == 1024
    assert inputs.input_type == "document"
    assert inputs.items[0].user_id is None
    assert len(inputs.items[0].input_hash) == 64
    assert inputs.skipped[0].reason == "NOT_FOUND"
