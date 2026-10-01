import logging
import time
from collections.abc import Sequence
from typing import Any

import httpx

from app.config import Settings
from app.embeddings.base import (
    EmbeddingBatch,
    EmbeddingConfigurationError,
    EmbeddingError,
    EmbeddingInputType,
)

logger = logging.getLogger(__name__)


class VoyageProvider:
    """Voyage AI embeddings over plain HTTPS (`POST {base_url}/embeddings`).

    The model and the output dimension come from settings and are sent on every call, so the vectors
    always live in the space core-api pinned. Inputs are never logged (they are CV and job text).
    """

    name = "voyage"

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.model = settings.embedding_model
        self.dimension = settings.embedding_dimension
        self._api_key = (
            settings.voyage_api_key.get_secret_value() if settings.voyage_api_key else None
        )
        self._client = client or httpx.AsyncClient(
            base_url=settings.voyage_base_url,
            timeout=settings.embedding_timeout_seconds,
        )

    async def embed(self, texts: Sequence[str], input_type: EmbeddingInputType) -> EmbeddingBatch:
        if self._api_key is None:
            raise EmbeddingConfigurationError("VOYAGE_API_KEY is not set")
        started = time.monotonic()
        try:
            response = await self._client.post(
                "/embeddings",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={
                    "input": list(texts),
                    "model": self.model,
                    "input_type": input_type.value,
                    "output_dimension": self.dimension,
                    "truncation": True,
                },
            )
        except httpx.HTTPError as e:
            raise EmbeddingError(f"voyage unreachable: {type(e).__name__}", retryable=True) from e
        latency_ms = int((time.monotonic() - started) * 1000)

        if response.status_code in (401, 403):
            raise EmbeddingConfigurationError(
                f"voyage refused the API key ({response.status_code})"
            )
        if response.status_code == 429 or response.status_code >= 500:
            raise EmbeddingError(
                f"voyage unavailable (status {response.status_code})", retryable=True
            )
        if response.status_code != 200:
            raise EmbeddingError(
                f"voyage rejected the request (status {response.status_code})", retryable=False
            )
        return self._parse(response, len(texts), latency_ms)

    def _parse(self, response: httpx.Response, expected: int, latency_ms: int) -> EmbeddingBatch:
        try:
            body: Any = response.json()
            data = sorted(body["data"], key=lambda item: item["index"])
            vectors = [[float(x) for x in item["embedding"]] for item in data]
            tokens = int(body["usage"]["total_tokens"])
        except (ValueError, KeyError, TypeError) as e:
            raise EmbeddingError("voyage returned an unreadable response", retryable=False) from e
        if len(vectors) != expected or any(len(v) != self.dimension for v in vectors):
            raise EmbeddingError(
                f"voyage returned {len(vectors)} vectors, or ones that are not {self.dimension} "
                "wide, for the requested model and dimension",
                retryable=False,
            )
        return EmbeddingBatch(
            vectors=vectors, model=self.model, input_tokens=tokens, latency_ms=latency_ms
        )

    async def aclose(self) -> None:
        await self._client.aclose()
