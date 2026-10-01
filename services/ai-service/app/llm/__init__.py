import logging

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
from app.llm.heuristic import HeuristicProvider
from app.llm.structured import StructuredResult, generate_structured

logger = logging.getLogger(__name__)


def build_provider(settings: Settings) -> LLMProvider:
    """Runtime provider from settings. The scripted FakeProvider is injected by tests."""
    match settings.llm_provider:
        case ProviderName.ANTHROPIC:
            return AnthropicProvider(settings)
        case ProviderName.FAKE:
            logger.warning(
                "LLM_PROVIDER=fake: match scores come from a keyword heuristic, not a model. "
                "For local runs and evals only; CV parsing is unavailable."
            )
            return HeuristicProvider()


__all__ = [
    "AnthropicProvider",
    "FakeProvider",
    "HeuristicProvider",
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
