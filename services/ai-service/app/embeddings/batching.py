import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable
from typing import Generic, Protocol, TypeVar

logger = logging.getLogger(__name__)


class Rejectable(Protocol):
    async def reject(self, requeue: bool = False) -> None: ...


M = TypeVar("M", bound=Rejectable)


class BatchCollector(Generic[M]):  # noqa: UP046 - mypy strict on 3.12 handles Generic fine; keeps it explicit
    """Gathers messages into batches for one handler call.

    Used as a queue consumer callback: each delivery is added to the pending batch, which is handled
    as soon as it holds `batch_size` messages, or `max_wait_seconds` after its first message. The
    handler settles (acks or rejects) every message it is given. Handling is serialised, so a queue
    makes at most one provider call at a time; the broker's prefetch window is what keeps the next
    batch filling meanwhile (it must be at least `batch_size`). Unacked messages go back to the
    queue if the connection drops, so a crash mid-batch only repeats work, which is harmless here.
    """

    def __init__(
        self,
        handler: Callable[[list[M]], Awaitable[None]],
        *,
        batch_size: int,
        max_wait_seconds: float,
    ) -> None:
        self._handler = handler
        self._batch_size = batch_size
        self._max_wait = max_wait_seconds
        self._pending: list[M] = []
        self._timer: asyncio.Task[None] | None = None
        self._lock = asyncio.Lock()

    async def __call__(self, message: M) -> None:
        self._pending.append(message)
        if len(self._pending) >= self._batch_size:
            await self.flush()
        elif self._timer is None:
            self._timer = asyncio.create_task(self._flush_after_wait(), name="batch-timer")

    async def flush(self) -> None:
        async with self._lock:
            self._cancel_timer()
            batch = self._pending[: self._batch_size]
            del self._pending[: self._batch_size]
            if self._pending:
                self._timer = asyncio.create_task(self._flush_after_wait(), name="batch-timer")
            if not batch:
                return
            try:
                await self._handler(batch)
            except Exception:
                # The handler is meant to settle everything itself; this is the last line of defence
                # so that no message is left unacked for ever.
                logger.exception("Batch handler failed; rejecting %d messages", len(batch))
                for message in batch:
                    with contextlib.suppress(Exception):
                        await message.reject(requeue=False)

    async def _flush_after_wait(self) -> None:
        await asyncio.sleep(self._max_wait)
        self._timer = None
        await self.flush()

    def _cancel_timer(self) -> None:
        if self._timer is not None and self._timer is not asyncio.current_task():
            self._timer.cancel()
        self._timer = None

    async def aclose(self) -> None:
        self._cancel_timer()
