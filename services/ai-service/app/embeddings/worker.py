"""Turns queued embed requests into stored vectors.

Flow for a batch of ids of one kind: ask core-api what needs embedding (it builds the text and hash,
and leaves out rows that are already current), embed the texts in one provider call, and hand the
vectors back. Every step is safe to repeat, so the queue's at-least-once delivery is harmless.
"""

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable, Sequence
from decimal import Decimal
from typing import Protocol, TypeVar
from uuid import UUID

from app.config import ModelPricing, Settings
from app.embeddings.base import EmbeddingError, EmbeddingInputType, EmbeddingProvider
from app.embeddings.core_client import (
    CoreApiClient,
    CoreApiError,
    EmbeddingKind,
    InputItem,
    ResultItem,
    UsageRecord,
)
from app.llm.pricing import estimate_cost_usd

logger = logging.getLogger(__name__)

T = TypeVar("T")

_FEATURE = {
    EmbeddingKind.JOB: "embed_job",
    EmbeddingKind.RESUME_VERSION: "embed_resume",
}


class EmbeddingFailedError(Exception):
    """A batch could not be embedded; `retryable` means retries ran out on a transient cause."""

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


class Delivery(Protocol):
    """The part of an AMQP message the worker uses (aio_pika's IncomingMessage satisfies it)."""

    body: bytes

    async def ack(self) -> None: ...

    async def reject(self, requeue: bool = False) -> None: ...


def parse_message(body: bytes) -> UUID | None:
    """The id a message names, or None if it is not `{"id": "<uuid>"}`."""
    try:
        document = json.loads(body)
        return UUID(str(document["id"]))
    except (ValueError, KeyError, TypeError):
        return None


class EmbeddingWorker:
    def __init__(
        self,
        settings: Settings,
        provider: EmbeddingProvider,
        core: CoreApiClient,
        *,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._provider = provider
        self._core = core
        self._max_attempts = settings.embedding_max_attempts
        self._backoff = settings.embedding_retry_backoff_seconds
        self._pricing: dict[str, ModelPricing] = settings.llm_pricing
        self._sleep = sleep

    def handler(self, kind: EmbeddingKind) -> Callable[[Sequence[Delivery]], Awaitable[None]]:
        """The batch handler for one queue (what BatchCollector calls with each batch)."""

        async def handle(messages: Sequence[Delivery]) -> None:
            await self.handle_messages(kind, messages)

        return handle

    async def handle_messages(self, kind: EmbeddingKind, messages: Sequence[Delivery]) -> None:
        """Settles every message: ack when handled (or nothing was left to do), reject into the
        dead-letter queue when malformed or unhandleable. Never raises."""
        by_id: dict[UUID, list[Delivery]] = {}
        for message in messages:
            message_id = parse_message(message.body)
            if message_id is None:
                logger.warning(
                    "Rejecting a malformed %s embed message (%d bytes)", kind, len(message.body)
                )
                await message.reject(requeue=False)
            else:
                by_id.setdefault(message_id, []).append(message)
        if not by_id:
            return

        ids = list(by_id)
        try:
            await self.embed_ids(kind, ids)
        except EmbeddingFailedError as e:
            if e.retryable or len(ids) == 1:
                logger.error("Embedding %d %s ids failed: %s", len(ids), kind, e)
                await self._settle(by_id, ids, ok=False)
                return
            # One bad row must not take the whole batch to the dead-letter queue: go one by one.
            logger.warning(
                "Embedding a batch of %d %s ids failed (%s); retrying one by one", len(ids), kind, e
            )
            for single in ids:
                try:
                    await self.embed_ids(kind, [single])
                    await self._settle(by_id, [single], ok=True)
                except EmbeddingFailedError as single_failure:
                    logger.error("Embedding %s %s failed: %s", kind, single, single_failure)
                    await self._settle(by_id, [single], ok=False)
            return
        except Exception:
            logger.exception("Unexpected error embedding %d %s ids", len(ids), kind)
            await self._settle(by_id, ids, ok=False)
            return
        await self._settle(by_id, ids, ok=True)

    @staticmethod
    async def _settle(by_id: dict[UUID, list[Delivery]], ids: list[UUID], *, ok: bool) -> None:
        for message_id in ids:
            for message in by_id[message_id]:
                if ok:
                    await message.ack()
                else:
                    await message.reject(requeue=False)

    async def embed_ids(self, kind: EmbeddingKind, ids: list[UUID]) -> int:
        """Embeds and stores the stale rows among `ids`; returns how many were stored."""
        inputs = await self._retrying(lambda: self._core.fetch_inputs(kind, ids))
        if inputs.model != self._provider.model or inputs.dimension != self._provider.dimension:
            raise EmbeddingFailedError(
                f"core-api pins {inputs.model}/{inputs.dimension} but this service embeds with "
                f"{self._provider.model}/{self._provider.dimension}; set the same EMBEDDING_MODEL "
                "and EMBEDDING_DIMENSION on both",
                retryable=False,
            )
        if not inputs.items:
            logger.info(
                "Nothing to embed for %d %s ids (%d skipped)", len(ids), kind, len(inputs.skipped)
            )
            return 0

        input_type = EmbeddingInputType(inputs.input_type)
        batch = await self._retrying(
            lambda: self._provider.embed([item.text for item in inputs.items], input_type)
        )
        items = [
            ResultItem(id=item.id, input_hash=item.input_hash, embedding=vector)
            for item, vector in zip(inputs.items, batch.vectors, strict=True)
        ]
        usage = self._usage(kind, inputs.items, batch.model, batch.input_tokens, batch.latency_ms)
        stored = await self._retrying(
            lambda: self._core.store_results(
                kind,
                model=batch.model,
                dimension=self._provider.dimension,
                items=items,
                usage=usage,
            )
        )
        for record in usage:
            logger.info(
                "ai usage user=%s feature=%s provider=%s model=%s inputTokens=%d outputTokens=0 "
                "costUsd=%s latencyMs=%d",
                record.user_id,
                record.feature,
                record.provider,
                record.model,
                record.input_tokens,
                record.cost_usd,
                record.latency_ms,
            )
        logger.info(
            "Embedded %s: applied=%d stale=%d missing=%d",
            kind,
            stored.applied,
            stored.stale,
            stored.missing,
        )
        return stored.applied

    def _usage(
        self,
        kind: EmbeddingKind,
        items: list[InputItem],
        model: str,
        tokens: int,
        latency_ms: int,
    ) -> list[UsageRecord]:
        """Jobs are system work: one record per call. Resumes belong to a user: the call's tokens
        are shared out over its resumes by text length (the provider reports one total), so each
        owner's record carries their share."""
        feature = _FEATURE[kind]

        def record(user_id: UUID | None, share: int, share_latency: int) -> UsageRecord:
            # The fake provider is free and has no price list entry to warn about.
            cost: Decimal = (
                Decimal(0)
                if self._provider.name == "fake"
                else estimate_cost_usd(self._pricing, model, share, 0)
            )
            return UsageRecord(
                user_id=user_id,
                feature=feature,
                provider=self._provider.name,
                model=model,
                input_tokens=share,
                cost_usd=cost,
                latency_ms=share_latency,
            )

        if kind is EmbeddingKind.JOB:
            return [record(None, tokens, latency_ms)]
        total_chars = sum(len(item.text) for item in items) or 1
        records: list[UsageRecord] = []
        assigned = 0
        for index, item in enumerate(items):
            share = (
                tokens - assigned
                if index == len(items) - 1
                else tokens * len(item.text) // total_chars
            )
            assigned += share
            records.append(record(item.user_id, share, latency_ms))
        return records

    async def _retrying(self, call: Callable[[], Awaitable[T]]) -> T:
        delay = self._backoff
        for attempt in range(1, self._max_attempts + 1):
            try:
                return await call()
            except (CoreApiError, EmbeddingError) as e:
                if not e.retryable:
                    raise EmbeddingFailedError(str(e), retryable=False) from e
                if attempt == self._max_attempts:
                    raise EmbeddingFailedError(str(e), retryable=True) from e
                logger.info(
                    "Transient failure (%s); retry %d/%d in %.1fs",
                    e,
                    attempt,
                    self._max_attempts,
                    delay,
                )
                await self._sleep(delay)
                delay *= 2
        raise AssertionError("unreachable")  # pragma: no cover
