import uuid

from fastapi.testclient import TestClient

from app.llm import FakeProvider, ModelTier


def test_llm_check_goes_through_provider(client: TestClient, fake_provider: FakeProvider) -> None:
    fake_provider.queue('{"status": "ok"}')
    user_id = uuid.uuid4()

    response = client.post("/v1/diagnostics/llm", json={"user_id": str(user_id)})

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    [usage] = body["usage"]
    assert usage["user_id"] == str(user_id)
    assert usage["feature"] == "diagnostics"
    assert usage["provider"] == "fake"
    assert usage["model"] == "fake-fast"
    assert usage["prompt_version"] == "diagnostics/v1"
    for key in ("input_tokens", "output_tokens", "cost_usd", "latency_ms"):
        assert key in usage

    [request] = fake_provider.requests
    assert request.tier is ModelTier.FAST
    assert request.user_id == user_id


def test_llm_check_retries_once_on_invalid_output(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue("not json", '```json\n{"status": "ok"}\n```')

    response = client.post("/v1/diagnostics/llm", json={"user_id": str(uuid.uuid4())})

    assert response.status_code == 200
    assert len(response.json()["usage"]) == 2
    assert len(fake_provider.requests) == 2
    assert "not valid JSON" in fake_provider.requests[1].user_message


def test_llm_check_fails_loudly_after_retry(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue('{"status": "nope"}', '{"status": "still nope"}')

    response = client.post("/v1/diagnostics/llm", json={"user_id": str(uuid.uuid4())})

    assert response.status_code == 502
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["title"] == "LLM output failed validation"
    assert len(fake_provider.requests) == 2


def test_llm_check_validates_body(client: TestClient) -> None:
    response = client.post("/v1/diagnostics/llm", json={"user_id": "not-a-uuid"})
    assert response.status_code == 422
