import logging
import time

import anthropic

from app.config import Settings
from app.llm.base import (
    LLMConfigurationError,
    LLMProviderError,
    LLMRefusalError,
    LLMRequest,
    LLMResponse,
    LLMUsage,
    ModelTier,
)
from app.llm.pricing import estimate_cost_usd

logger = logging.getLogger(__name__)


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, settings: Settings, client: anthropic.AsyncAnthropic | None = None) -> None:
        self._settings = settings
        self._models = {
            ModelTier.FAST: settings.llm_model_fast,
            ModelTier.STRONG: settings.llm_model_strong,
        }
        if client is not None:
            self._client: anthropic.AsyncAnthropic | None = client
        elif settings.anthropic_api_key is not None:
            self._client = anthropic.AsyncAnthropic(
                api_key=settings.anthropic_api_key.get_secret_value(),
                timeout=settings.llm_timeout_seconds,
                max_retries=settings.llm_max_retries,
                default_headers=(
                    {"anthropic-workspace-id": settings.anthropic_workspace_id}
                    if settings.anthropic_workspace_id
                    else None
                ),
            )
        else:
            # Service still starts (health, queues); LLM routes fail with a clear error.
            self._client = None

    async def generate(self, request: LLMRequest) -> LLMResponse:
        if self._client is None:
            raise LLMConfigurationError("ANTHROPIC_API_KEY is not set")

        model = self._models[request.tier]
        started = time.perf_counter()
        try:
            message = await self._client.messages.create(
                model=model,
                max_tokens=request.max_tokens or self._settings.llm_max_tokens,
                system=request.system,
                messages=[{"role": "user", "content": request.user_message}],
            )
        except anthropic.RateLimitError as e:
            raise LLMProviderError("Anthropic rate limit exceeded", retryable=True) from e
        except anthropic.APIStatusError as e:
            raise LLMProviderError(
                f"Anthropic API error (status {e.status_code})",
                retryable=e.status_code >= 500,
            ) from e
        except anthropic.APIConnectionError as e:
            raise LLMProviderError("Could not reach Anthropic API", retryable=True) from e
        latency_ms = int((time.perf_counter() - started) * 1000)

        input_tokens = message.usage.input_tokens
        output_tokens = message.usage.output_tokens
        usage = LLMUsage(
            user_id=request.user_id,
            feature=request.feature,
            provider=self.name,
            model=model,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_usd=estimate_cost_usd(
                self._settings.llm_pricing, model, input_tokens, output_tokens
            ),
            latency_ms=latency_ms,
            prompt_version=request.prompt_version,
            pricing_version=self._settings.llm_pricing_version,
        )
        if message.stop_reason == "refusal":
            # The provider billed the call even though the model declined.
            refusal = LLMRefusalError("Model declined the request")
            refusal.usage = [usage]
            raise refusal
        if message.stop_reason == "max_tokens":
            logger.warning(
                "LLM output truncated at max_tokens (feature=%s, model=%s)", request.feature, model
            )

        text = "".join(block.text for block in message.content if block.type == "text")
        return LLMResponse(text=text, usage=usage)

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.close()
