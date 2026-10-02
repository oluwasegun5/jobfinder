"""The overall deadline of an interview request: cancel what is running, report what was billed,
and stay below core-api's read timeout so core-api is still there to receive the report
(docs/adr/0034-mock-interview.md, addendum)."""

import asyncio
import time
from typing import Any

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr, ValidationError

from app.config import DEADLINE_MARGIN_SECONDS, Settings
from app.interview.mock_service import SUMMARY_FEATURE, TURN_FEATURE
from app.interview.service import QUESTIONS_FEATURE
from app.llm import FakeProvider, LLMRequest, LLMResponse
from app.main import create_app
from app.security import SERVICE_TOKEN_HEADER
from tests.conftest import TEST_TOKEN
from tests.fixtures import interview as ix
from tests.fixtures import mock_interview as mx

DEADLINE = 0.4


class HangingProvider:
    """Answers the first `answer_calls` calls from a FakeProvider, then never answers."""

    name = "hanging"

    def __init__(self, *replies: str, answer_calls: int) -> None:
        self._fake = FakeProvider(replies)
        self._answer_calls = answer_calls
        self.calls = 0
        self.started_hanging = 0
        self.cancelled = 0

    async def generate(self, request: LLMRequest) -> LLMResponse:
        self.calls += 1
        if self.calls > self._answer_calls:
            self.started_hanging += 1
            try:
                await asyncio.sleep(3600)
            except asyncio.CancelledError:
                self.cancelled += 1
                raise
        return await self._fake.generate(request)

    async def aclose(self) -> None:
        return None


def client_for(provider: HangingProvider) -> TestClient:
    settings = Settings(
        ai_service_token=SecretStr(TEST_TOKEN),
        rabbitmq_enabled=False,
        interview_prep_deadline_seconds=DEADLINE,
        mock_interview_deadline_seconds=DEADLINE,
    )
    return TestClient(
        create_app(settings, provider=provider), headers={SERVICE_TOKEN_HEADER: TEST_TOKEN}
    )


def assert_deadline_problem(response: Any, elapsed: float) -> dict[str, Any]:
    # The same problem+json shape as every failure that carries usage.
    assert response.status_code == 504
    assert response.headers["content-type"].startswith("application/problem+json")
    body: dict[str, Any] = response.json()
    assert body["code"] == "llm_deadline_exceeded"
    assert body["retryable"] is True
    assert body["status"] == 504
    assert set(body) >= {"type", "title", "status", "detail", "code", "retryable", "usage"}
    assert elapsed < DEADLINE + 2.0, (
        "the error must come at the deadline, not when the provider gives up"
    )
    return body


# --- /v1/interview-prep: two calls, the second stalls ---


def test_a_stalled_brief_call_fails_at_the_deadline_and_reports_the_billed_questions_call() -> None:
    provider = HangingProvider(ix.questions_reply(), answer_calls=1)
    with client_for(provider) as client:
        started = time.monotonic()
        response = client.post("/v1/interview-prep", json=ix.request_body())
        elapsed = time.monotonic() - started

    body = assert_deadline_problem(response, elapsed)
    assert [u["feature"] for u in body["usage"]] == [QUESTIONS_FEATURE]
    assert body["usage"][0]["call_id"]
    assert body["usage"][0]["user_id"] == ix.request_body()["user_id"]
    # The call in flight was cancelled, not left running.
    assert (provider.started_hanging, provider.cancelled) == (1, 1)


def test_a_stalled_first_call_reports_no_usage_and_is_cancelled() -> None:
    provider = HangingProvider(answer_calls=0)
    with client_for(provider) as client:
        started = time.monotonic()
        response = client.post("/v1/interview-prep", json=ix.request_body())
        elapsed = time.monotonic() - started

    body = assert_deadline_problem(response, elapsed)
    assert body["usage"] == []
    assert provider.cancelled == 1


# --- /v1/mock-interview/turn and /summary ---


def test_a_stalled_retry_after_invalid_output_reports_the_billed_first_attempt() -> None:
    # The first attempt returned text that is not the schema (billed), the retry stalls.
    provider = HangingProvider("this is not json", answer_calls=1)
    with client_for(provider) as client:
        started = time.monotonic()
        response = client.post("/v1/mock-interview/turn", json=mx.request_body())
        elapsed = time.monotonic() - started

    body = assert_deadline_problem(response, elapsed)
    assert [u["feature"] for u in body["usage"]] == [TURN_FEATURE]
    assert provider.cancelled == 1


def test_a_stalled_turn_call_fails_at_the_deadline() -> None:
    provider = HangingProvider(answer_calls=0)
    with client_for(provider) as client:
        started = time.monotonic()
        response = client.post("/v1/mock-interview/turn", json=mx.request_body())
        elapsed = time.monotonic() - started

    body = assert_deadline_problem(response, elapsed)
    assert body["usage"] == []
    assert provider.cancelled == 1


def test_a_stalled_summary_call_fails_at_the_deadline() -> None:
    provider = HangingProvider("not json either", answer_calls=1)
    with client_for(provider) as client:
        started = time.monotonic()
        response = client.post("/v1/mock-interview/summary", json=mx.summary_request())
        elapsed = time.monotonic() - started

    body = assert_deadline_problem(response, elapsed)
    assert [u["feature"] for u in body["usage"]] == [SUMMARY_FEATURE]
    assert provider.cancelled == 1


def test_a_request_that_finishes_inside_the_deadline_is_unaffected() -> None:
    provider = HangingProvider(mx.turn_reply(question=mx.next_question()), answer_calls=1)
    with client_for(provider) as client:
        response = client.post("/v1/mock-interview/turn", json=mx.request_body())

    assert response.status_code == 200
    assert len(response.json()["usage"]) == 1
    assert provider.cancelled == 0


# --- the relationship to core-api's read timeouts ---


def test_the_default_deadlines_are_below_core_apis_read_timeouts() -> None:
    settings = Settings(ai_service_token=SecretStr(TEST_TOKEN), rabbitmq_enabled=False)

    assert settings.interview_prep_deadline_seconds == 120.0
    assert settings.interview_prep_deadline_seconds < 150.0  # app.interview.read-timeout
    assert settings.mock_interview_deadline_seconds < 90.0  # app.interview.mock.read-timeout
    assert (
        settings.interview_prep_deadline_seconds
        <= settings.core_api_interview_read_timeout_seconds - DEADLINE_MARGIN_SECONDS
    )
    assert (
        settings.mock_interview_deadline_seconds
        <= settings.core_api_mock_interview_read_timeout_seconds - DEADLINE_MARGIN_SECONDS
    )


@pytest.mark.parametrize(
    "overrides",
    [
        {"interview_prep_deadline_seconds": 150.0},
        {"interview_prep_deadline_seconds": 146.0},
        {"mock_interview_deadline_seconds": 120.0},
        {"mock_interview_deadline_seconds": 90.0},
        {"interview_prep_deadline_seconds": 100.0, "core_api_interview_read_timeout_seconds": 60.0},
    ],
)
def test_a_deadline_that_is_not_below_the_read_timeout_is_refused_at_startup(
    overrides: dict[str, float],
) -> None:
    with pytest.raises(ValidationError, match="read timeout"):
        Settings(ai_service_token=SecretStr(TEST_TOKEN), rabbitmq_enabled=False, **overrides)  # type: ignore[arg-type]
