from decimal import Decimal
from uuid import UUID

from pydantic import BaseModel, ConfigDict

from app.llm import LLMUsage


class UsageRecord(BaseModel):
    """One LLM call, returned to core-api for the ai_calls ledger (PLAN.md §5 billing)."""

    model_config = ConfigDict(frozen=True)

    user_id: UUID
    feature: str
    provider: str
    model: str
    input_tokens: int
    output_tokens: int
    cost_usd: Decimal
    latency_ms: int
    prompt_version: str

    @classmethod
    def from_usage(cls, usage: LLMUsage) -> "UsageRecord":
        return cls(
            user_id=usage.user_id,
            feature=usage.feature,
            provider=usage.provider,
            model=usage.model,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            cost_usd=usage.cost_usd,
            latency_ms=usage.latency_ms,
            prompt_version=usage.prompt_version,
        )
