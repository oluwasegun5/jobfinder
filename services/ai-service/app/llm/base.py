"""Provider-agnostic LLM interface (PLAN.md D7). All LLM calls go through an LLMProvider."""

from dataclasses import dataclass, field
from decimal import Decimal
from enum import StrEnum
from typing import Protocol, runtime_checkable
from uuid import UUID, uuid4


class ModelTier(StrEnum):
    """Model class from PLAN.md §7; the concrete model name comes from settings."""

    FAST = "fast"
    STRONG = "strong"


@dataclass(frozen=True, slots=True)
class LLMRequest:
    user_id: UUID
    feature: str
    prompt_version: str
    tier: ModelTier
    system: str
    user_message: str
    max_tokens: int | None = None


@dataclass(frozen=True, slots=True)
class LLMUsage:
    """Per-call usage record, returned to core-api for the credit ledger."""

    user_id: UUID
    feature: str
    provider: str
    model: str
    input_tokens: int
    output_tokens: int
    cost_usd: Decimal
    latency_ms: int
    prompt_version: str
    # Idempotency key for the ledger: core-api records each call_id once, however often it is sent.
    call_id: UUID = field(default_factory=uuid4)
    # Version of the pinned price list that produced cost_usd (settings.llm_pricing_version).
    pricing_version: str = ""


@dataclass(frozen=True, slots=True)
class LLMResponse:
    text: str
    usage: LLMUsage


class LLMError(Exception):
    """Base error for anything that goes wrong talking to an LLM.

    `usage` lists the calls that completed (and were billed) before the failure, so the caller can
    still report them to the ledger: a validation retry that fails, or a refusal, costs money too.
    """

    def __init__(self, *args: object) -> None:
        super().__init__(*args)
        self.usage: list[LLMUsage] = []


class LLMConfigurationError(LLMError):
    """The provider is not configured (e.g. missing API key)."""


class LLMProviderError(LLMError):
    """The provider call failed; `retryable` tells callers whether a retry may help."""

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


class LLMDeadlineError(LLMError):
    """The overall deadline of a request ran out. In-flight provider calls were cancelled; `usage`
    lists the calls that had completed (and were billed) before that."""


class LLMRefusalError(LLMError):
    """The model declined the request."""


class LLMOutputValidationError(LLMError):
    """The model output did not match the expected schema after the allowed retry."""


@runtime_checkable
class LLMProvider(Protocol):
    name: str

    async def generate(self, request: LLMRequest) -> LLMResponse: ...

    async def aclose(self) -> None: ...
