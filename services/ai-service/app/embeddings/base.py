"""Provider-agnostic embedding interface (PLAN.md D7): every embedding call goes through it."""

from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol, runtime_checkable


class EmbeddingInputType(StrEnum):
    """How the provider should treat the text (Voyage prepends a different prompt for each)."""

    DOCUMENT = "document"
    QUERY = "query"


@dataclass(frozen=True, slots=True)
class EmbeddingBatch:
    """Vectors in the order of the texts, and what the call cost in tokens and time."""

    vectors: list[list[float]]
    model: str
    input_tokens: int
    latency_ms: int


class EmbeddingError(Exception):
    """A provider call failed; `retryable` tells callers whether trying again may help."""

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


class EmbeddingConfigurationError(EmbeddingError):
    """The provider is not configured or refuses our credentials; retrying cannot help."""

    def __init__(self, message: str) -> None:
        super().__init__(message, retryable=False)


@runtime_checkable
class EmbeddingProvider(Protocol):
    name: str
    model: str
    dimension: int

    async def embed(
        self, texts: Sequence[str], input_type: EmbeddingInputType
    ) -> EmbeddingBatch: ...

    async def aclose(self) -> None: ...
