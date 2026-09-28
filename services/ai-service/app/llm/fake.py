from collections import deque
from collections.abc import Iterable
from decimal import Decimal

from app.llm.base import LLMRequest, LLMResponse, LLMUsage, ModelTier


class FakeProvider:
    """Deterministic in-memory provider for tests and offline local runs.

    Replies are served from a FIFO queue of scripted outputs; once empty, `default_reply`
    is returned. Every request is recorded in `requests` for assertions.
    """

    name = "fake"

    def __init__(
        self,
        replies: Iterable[str] = (),
        *,
        default_reply: str = "{}",
        models: dict[ModelTier, str] | None = None,
    ) -> None:
        self._replies: deque[str] = deque(replies)
        self._default_reply = default_reply
        self._models = models or {ModelTier.FAST: "fake-fast", ModelTier.STRONG: "fake-strong"}
        self.requests: list[LLMRequest] = []

    def queue(self, *replies: str) -> None:
        self._replies.extend(replies)

    async def generate(self, request: LLMRequest) -> LLMResponse:
        self.requests.append(request)
        text = self._replies.popleft() if self._replies else self._default_reply
        return LLMResponse(
            text=text,
            usage=LLMUsage(
                user_id=request.user_id,
                feature=request.feature,
                provider=self.name,
                model=self._models[request.tier],
                input_tokens=len(request.system.split()) + len(request.user_message.split()),
                output_tokens=len(text.split()),
                cost_usd=Decimal(0),
                latency_ms=0,
                prompt_version=request.prompt_version,
            ),
        )

    async def aclose(self) -> None:
        return None
