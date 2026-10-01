import json
import math
from typing import Any

import httpx
import pytest
from pydantic import SecretStr

from app.config import EmbeddingProviderName, Settings
from app.embeddings import (
    EmbeddingConfigurationError,
    EmbeddingError,
    EmbeddingInputType,
    FakeEmbeddingProvider,
    VoyageProvider,
    build_embedding_provider,
)

TOKEN = "test-service-token-0123456789abcdef"


def _settings(**overrides: Any) -> Settings:
    base: dict[str, Any] = {
        "ai_service_token": SecretStr(TOKEN),
        "rabbitmq_enabled": False,
        "embedding_dimension": 4,
        "voyage_api_key": SecretStr("voyage-test-key"),
    }
    return Settings(**{**base, **overrides})


def _voyage(handler: Any, **overrides: Any) -> VoyageProvider:
    client = httpx.AsyncClient(
        base_url="https://voyage.test/v1", transport=httpx.MockTransport(handler)
    )
    return VoyageProvider(_settings(**overrides), client)


async def test_fake_embeddings_are_deterministic_unit_length_and_input_type_aware() -> None:
    provider = FakeEmbeddingProvider(model="fake-embed-1024", dimension=1024)

    first = await provider.embed(["hello", "world"], EmbeddingInputType.DOCUMENT)
    again = await provider.embed(["hello"], EmbeddingInputType.DOCUMENT)
    as_query = await provider.embed(["hello"], EmbeddingInputType.QUERY)

    assert first.vectors[0] == again.vectors[0]
    assert first.vectors[0] != first.vectors[1]
    assert first.vectors[0] != as_query.vectors[0]
    assert all(len(v) == 1024 for v in first.vectors)
    assert math.isclose(math.sqrt(sum(x * x for x in first.vectors[0])), 1.0, rel_tol=1e-9)
    assert first.model == "fake-embed-1024"
    assert first.input_tokens == 2


def test_the_fake_model_name_must_say_fake() -> None:
    with pytest.raises(ValueError, match="fake-"):
        FakeEmbeddingProvider(model="voyage-4", dimension=8)
    with pytest.raises(ValueError, match="fake-"):
        _settings(embedding_provider=EmbeddingProviderName.FAKE, embedding_model="voyage-4")


def test_the_factory_follows_the_configured_provider() -> None:
    fake = build_embedding_provider(
        _settings(embedding_provider=EmbeddingProviderName.FAKE, embedding_model="fake-x")
    )
    assert isinstance(fake, FakeEmbeddingProvider)
    assert isinstance(build_embedding_provider(_settings()), VoyageProvider)


async def test_voyage_sends_the_pinned_model_and_dimension_and_orders_by_index() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers["authorization"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "object": "list",
                "model": "voyage-4",
                "data": [
                    {"object": "embedding", "index": 1, "embedding": [0.0, 1.0, 0.0, 0.0]},
                    {"object": "embedding", "index": 0, "embedding": [1.0, 0.0, 0.0, 0.0]},
                ],
                "usage": {"total_tokens": 17},
            },
        )

    provider = _voyage(handler)
    batch = await provider.embed(["first", "second"], EmbeddingInputType.QUERY)

    assert seen["url"] == "https://voyage.test/v1/embeddings"
    assert seen["auth"] == "Bearer voyage-test-key"
    assert seen["body"] == {
        "input": ["first", "second"],
        "model": "voyage-4",
        "input_type": "query",
        "output_dimension": 4,
        "truncation": True,
    }
    assert batch.vectors == [[1.0, 0.0, 0.0, 0.0], [0.0, 1.0, 0.0, 0.0]]
    assert batch.input_tokens == 17
    assert batch.model == "voyage-4"


async def test_voyage_without_a_key_is_a_configuration_error() -> None:
    provider = _voyage(lambda r: httpx.Response(200), voyage_api_key=None)
    with pytest.raises(EmbeddingConfigurationError):
        await provider.embed(["x"], EmbeddingInputType.DOCUMENT)


@pytest.mark.parametrize(
    ("status", "retryable", "configuration"),
    [(429, True, False), (503, True, False), (400, False, False), (401, False, True)],
)
async def test_voyage_failures_say_whether_a_retry_may_help(
    status: int, retryable: bool, configuration: bool
) -> None:
    provider = _voyage(lambda r: httpx.Response(status, json={"detail": "nope"}))
    with pytest.raises(EmbeddingError) as raised:
        await provider.embed(["x"], EmbeddingInputType.DOCUMENT)
    assert raised.value.retryable is retryable
    assert isinstance(raised.value, EmbeddingConfigurationError) is configuration


async def test_voyage_network_errors_are_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("boom")

    with pytest.raises(EmbeddingError) as raised:
        await _voyage(handler).embed(["x"], EmbeddingInputType.DOCUMENT)
    assert raised.value.retryable


@pytest.mark.parametrize(
    "body",
    [
        {"data": [{"index": 0, "embedding": [1.0, 2.0]}], "usage": {"total_tokens": 1}},
        {"data": [], "usage": {"total_tokens": 1}},
        {"unexpected": True},
    ],
)
async def test_voyage_rejects_responses_that_do_not_fit_the_request(body: dict[str, Any]) -> None:
    provider = _voyage(lambda r: httpx.Response(200, json=body))
    with pytest.raises(EmbeddingError) as raised:
        await provider.embed(["x"], EmbeddingInputType.DOCUMENT)
    assert not raised.value.retryable
