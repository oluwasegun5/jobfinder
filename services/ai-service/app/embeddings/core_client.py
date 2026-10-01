"""ai-service's side of core-api's internal embeddings endpoints (`/internal/v1/embeddings/*`).

Both calls carry the shared service token. Bodies hold CV and job text, so they are never logged.
"""

import logging
from decimal import Decimal
from enum import StrEnum
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from pydantic.alias_generators import to_camel

from app.config import Settings
from app.security import SERVICE_TOKEN_HEADER

logger = logging.getLogger(__name__)


class EmbeddingKind(StrEnum):
    JOB = "JOB"
    RESUME_VERSION = "RESUME_VERSION"


class CoreApiError(Exception):
    """core-api refused or could not be reached. `retryable` says whether trying again may help."""

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


class _Wire(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class InputItem(_Wire):
    id: UUID
    user_id: UUID | None = None
    text: str
    input_hash: str


class Skipped(_Wire):
    id: UUID
    reason: str


class Inputs(_Wire):
    """What core-api wants embedded, in which space, and why it left some ids out."""

    model: str
    dimension: int
    input_type: str
    items: list[InputItem] = Field(default_factory=list)
    skipped: list[Skipped] = Field(default_factory=list)


class ResultItem(_Wire):
    id: UUID
    input_hash: str
    embedding: list[float]


class UsageRecord(_Wire):
    """One provider call, for core-api's usage log (the billing ledger comes in Phase 3)."""

    user_id: UUID | None
    feature: str
    provider: str
    model: str
    input_tokens: int
    cost_usd: Decimal
    latency_ms: int


class Stored(_Wire):
    applied: int
    stale: int
    missing: int


class CoreApiClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self._client = client or httpx.AsyncClient(
            base_url=settings.core_api_base_url,
            timeout=settings.core_api_timeout_seconds,
            headers={SERVICE_TOKEN_HEADER: settings.ai_service_token.get_secret_value()},
        )

    async def fetch_inputs(self, kind: EmbeddingKind, ids: list[UUID]) -> Inputs:
        body = await self._call(
            "POST",
            "/internal/v1/embeddings/inputs",
            {"kind": kind.value, "ids": [str(i) for i in ids]},
        )
        try:
            return Inputs.model_validate(body)
        except ValidationError as e:
            raise CoreApiError(
                "core-api returned an unexpected inputs response", retryable=False
            ) from e

    async def store_results(
        self,
        kind: EmbeddingKind,
        *,
        model: str,
        dimension: int,
        items: list[ResultItem],
        usage: list[UsageRecord],
    ) -> Stored:
        body = await self._call(
            "PUT",
            "/internal/v1/embeddings/results",
            {
                "kind": kind.value,
                "model": model,
                "dimension": dimension,
                "items": [i.model_dump(by_alias=True, mode="json") for i in items],
                "usage": [u.model_dump(by_alias=True, mode="json") for u in usage],
            },
        )
        try:
            return Stored.model_validate(body)
        except ValidationError as e:
            raise CoreApiError(
                "core-api returned an unexpected results response", retryable=False
            ) from e

    async def _call(self, method: str, path: str, payload: dict[str, object]) -> object:
        try:
            response = await self._client.request(method, path, json=payload)
        except httpx.HTTPError as e:
            raise CoreApiError(f"core-api unreachable: {type(e).__name__}", retryable=True) from e
        if response.status_code == 200:
            try:
                return response.json()
            except ValueError as e:
                raise CoreApiError("core-api returned a non-JSON body", retryable=False) from e
        retryable = response.status_code == 429 or response.status_code >= 500
        logger.warning("core-api refused %s %s (status=%d)", method, path, response.status_code)
        raise CoreApiError(f"core-api answered {response.status_code}", retryable=retryable)

    async def aclose(self) -> None:
        await self._client.aclose()
