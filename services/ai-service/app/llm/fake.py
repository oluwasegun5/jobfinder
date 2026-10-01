from collections import deque
from collections.abc import Iterable
from decimal import Decimal

from app.config import ModelPricing
from app.llm.base import LLMRequest, LLMResponse, LLMUsage, ModelTier
from app.llm.pricing import estimate_cost_usd


class FakeProvider:
    """Deterministic in-memory provider for tests and offline local runs.

    Replies are served from a FIFO queue of scripted outputs; once empty, `default_reply`
    is returned. Every request is recorded in `requests` for assertions. It is free unless given a
    `pricing` table that lists its model names (used to exercise cost tracking without a provider).
    """

    name = "fake"

    def __init__(
        self,
        replies: Iterable[str] = (),
        *,
        default_reply: str = "{}",
        models: dict[ModelTier, str] | None = None,
        pricing: dict[str, ModelPricing] | None = None,
        pricing_version: str = "fake",
    ) -> None:
        self._pricing = pricing or {}
        self._pricing_version = pricing_version
        self._replies: deque[str] = deque(replies)
        self._default_reply = default_reply
        self._models = models or {ModelTier.FAST: "fake-fast", ModelTier.STRONG: "fake-strong"}
        self.requests: list[LLMRequest] = []

    def queue(self, *replies: str) -> None:
        self._replies.extend(replies)

    async def generate(self, request: LLMRequest) -> LLMResponse:
        self.requests.append(request)
        text = self._replies.popleft() if self._replies else self._default_reply
        model = self._models[request.tier]
        input_tokens = len(request.system.split()) + len(request.user_message.split())
        output_tokens = len(text.split())
        return LLMResponse(
            text=text,
            usage=LLMUsage(
                user_id=request.user_id,
                feature=request.feature,
                provider=self.name,
                model=model,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                cost_usd=(
                    estimate_cost_usd(self._pricing, model, input_tokens, output_tokens)
                    if model in self._pricing
                    else Decimal(0)
                ),
                latency_ms=0,
                prompt_version=request.prompt_version,
                pricing_version=self._pricing_version,
            ),
        )

    async def aclose(self) -> None:
        return None
