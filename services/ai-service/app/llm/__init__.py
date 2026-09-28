from app.config import ProviderName, Settings
from app.llm.anthropic_provider import AnthropicProvider
from app.llm.base import (
    LLMConfigurationError,
    LLMError,
    LLMOutputValidationError,
    LLMProvider,
    LLMProviderError,
    LLMRefusalError,
    LLMRequest,
    LLMResponse,
    LLMUsage,
    ModelTier,
)
from app.llm.fake import FakeProvider
from app.llm.structured import StructuredResult, generate_structured


def build_provider(settings: Settings) -> LLMProvider:
    """Runtime provider from settings. FakeProvider is for tests and is injected directly."""
    match settings.llm_provider:
        case ProviderName.ANTHROPIC:
            return AnthropicProvider(settings)


__all__ = [
    "AnthropicProvider",
    "FakeProvider",
    "LLMConfigurationError",
    "LLMError",
    "LLMOutputValidationError",
    "LLMProvider",
    "LLMProviderError",
    "LLMRefusalError",
    "LLMRequest",
    "LLMResponse",
    "LLMUsage",
    "ModelTier",
    "StructuredResult",
    "build_provider",
    "generate_structured",
]
