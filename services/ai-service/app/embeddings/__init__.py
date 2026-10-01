from app.config import EmbeddingProviderName, Settings
from app.embeddings.base import (
    EmbeddingBatch,
    EmbeddingConfigurationError,
    EmbeddingError,
    EmbeddingInputType,
    EmbeddingProvider,
)
from app.embeddings.fake import FakeEmbeddingProvider
from app.embeddings.voyage import VoyageProvider


def build_embedding_provider(settings: Settings) -> EmbeddingProvider:
    match settings.embedding_provider:
        case EmbeddingProviderName.VOYAGE:
            return VoyageProvider(settings)
        case EmbeddingProviderName.FAKE:
            return FakeEmbeddingProvider(
                model=settings.embedding_model, dimension=settings.embedding_dimension
            )


__all__ = [
    "EmbeddingBatch",
    "EmbeddingConfigurationError",
    "EmbeddingError",
    "EmbeddingInputType",
    "EmbeddingProvider",
    "FakeEmbeddingProvider",
    "VoyageProvider",
    "build_embedding_provider",
]
