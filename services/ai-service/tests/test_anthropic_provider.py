import uuid
from decimal import Decimal
from typing import Any

import anthropic
import httpx2
import pytest
from anthropic.types import Message
from pydantic import SecretStr

from app.config import ProviderName, Settings
from app.llm import (
    AnthropicProvider,
    LLMConfigurationError,
    LLMProviderError,
    LLMRefusalError,
    LLMRequest,
    ModelTier,
    build_provider,
)


def _settings(**overrides: Any) -> Settings:
    base: dict[str, Any] = {
        "ai_service_token": SecretStr("x" * 32),
        "anthropic_api_key": SecretStr("sk-test"),
        "llm_model_fast": "claude-haiku-4-5",
        "llm_model_strong": "claude-sonnet-5",
        "rabbitmq_enabled": False,
    }
    return Settings(**(base | overrides))


def _request(tier: ModelTier = ModelTier.FAST) -> LLMRequest:
    return LLMRequest(
        user_id=uuid.uuid4(),
        feature="test",
        prompt_version="test/v1",
        tier=tier,
        system="system",
        user_message="hello",
    )


def _message(stop_reason: str = "end_turn", text: str = "hi") -> Message:
    return Message.model_validate(
        {
            "id": "msg_1",
            "type": "message",
            "role": "assistant",
            "model": "claude-haiku-4-5",
            "content": [{"type": "text", "text": text}],
            "stop_reason": stop_reason,
            "stop_sequence": None,
            "usage": {"input_tokens": 1_000, "output_tokens": 200},
        }
    )


class _StubMessages:
    def __init__(self, result: Message | Exception) -> None:
        self.result = result
        self.calls: list[dict[str, Any]] = []

    async def create(self, **kwargs: Any) -> Message:
        self.calls.append(kwargs)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


class _StubClient:
    def __init__(self, result: Message | Exception) -> None:
        self.messages = _StubMessages(result)

    async def close(self) -> None:
        return None


def _provider(settings: Settings, result: Message | Exception) -> tuple[AnthropicProvider, Any]:
    stub = _StubClient(result)
    return AnthropicProvider(settings, client=stub), stub  # type: ignore[arg-type]


@pytest.mark.parametrize(
    ("tier", "model"),
    [(ModelTier.FAST, "claude-haiku-4-5"), (ModelTier.STRONG, "claude-sonnet-5")],
)
async def test_model_comes_from_settings(tier: ModelTier, model: str) -> None:
    provider, stub = _provider(_settings(), _message())

    response = await provider.generate(_request(tier))

    assert stub.messages.calls[0]["model"] == model
    assert response.usage.model == model


async def test_usage_and_cost_are_reported() -> None:
    provider, _ = _provider(_settings(), _message(text="hello there"))
    request = _request()

    response = await provider.generate(request)

    assert response.text == "hello there"
    usage = response.usage
    assert usage.user_id == request.user_id
    assert (usage.input_tokens, usage.output_tokens) == (1_000, 200)
    # haiku: $1/MTok in, $5/MTok out
    assert usage.cost_usd == Decimal("0.002000")
    assert usage.prompt_version == "test/v1"
    assert usage.provider == "anthropic"


async def test_refusal_raises() -> None:
    provider, _ = _provider(_settings(), _message(stop_reason="refusal", text=""))
    with pytest.raises(LLMRefusalError):
        await provider.generate(_request())


async def test_rate_limit_is_retryable_provider_error() -> None:
    req = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
    error = anthropic.RateLimitError(
        "rate limited", response=httpx2.Response(429, request=req), body=None
    )
    provider, _ = _provider(_settings(), error)
    with pytest.raises(LLMProviderError) as exc_info:
        await provider.generate(_request())
    assert exc_info.value.retryable


async def test_missing_api_key_fails_with_configuration_error() -> None:
    provider = AnthropicProvider(_settings(anthropic_api_key=None))
    with pytest.raises(LLMConfigurationError):
        await provider.generate(_request())


def test_build_provider_follows_settings() -> None:
    assert isinstance(
        build_provider(_settings(llm_provider=ProviderName.ANTHROPIC)), AnthropicProvider
    )


def test_model_names_can_be_set_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", "y" * 32)
    monkeypatch.setenv("LLM_MODEL_FAST", "some-fast-model")
    monkeypatch.setenv("LLM_MODEL_STRONG", "some-strong-model")
    settings = Settings()
    assert settings.llm_model_fast == "some-fast-model"
    assert settings.llm_model_strong == "some-strong-model"


def test_blank_api_key_counts_as_unset(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", "y" * 32)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "")
    assert Settings().anthropic_api_key is None


def test_short_service_token_is_rejected() -> None:
    with pytest.raises(ValueError, match="ai_service_token"):
        _settings(ai_service_token=SecretStr("short"))


async def test_a_refusal_still_reports_the_billed_call() -> None:
    provider, _ = _provider(_settings(), _message(stop_reason="refusal", text=""))
    with pytest.raises(LLMRefusalError) as exc_info:
        await provider.generate(_request())
    (usage,) = exc_info.value.usage
    assert usage.feature == _request().feature
    assert usage.provider == "anthropic"
    assert usage.input_tokens > 0 or usage.output_tokens >= 0


def _sent_headers(**overrides: Any) -> httpx2.Headers:
    provider = AnthropicProvider(_settings(**overrides))
    assert provider._client is not None
    return provider._client.default_headers


def test_workspace_id_is_sent_as_header_when_configured() -> None:
    headers = _sent_headers(anthropic_workspace_id="wrkspc_test")
    assert headers["anthropic-workspace-id"] == "wrkspc_test"


def test_no_workspace_header_by_default_and_blank_counts_as_unset() -> None:
    assert "anthropic-workspace-id" not in _sent_headers()
    assert "anthropic-workspace-id" not in _sent_headers(anthropic_workspace_id="  ")
