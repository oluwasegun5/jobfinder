"""RabbitMQ consumer (aio-pika): one robust connection, several queues, one handler each."""

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import StrEnum

import aio_pika
from aio_pika.abc import AbstractIncomingMessage, AbstractRobustConnection

logger = logging.getLogger(__name__)

NOOP_QUEUE = "ai.noop"

MessageHandler = Callable[[AbstractIncomingMessage], Awaitable[None]]


@dataclass(frozen=True, slots=True)
class AmqpParams:
    host: str
    port: int
    login: str
    password: str
    virtualhost: str = "/"

    def __repr__(self) -> str:
        return f"AmqpParams(host={self.host!r}, port={self.port}, login={self.login!r})"


class ConsumerState(StrEnum):
    DISABLED = "DISABLED"
    CONNECTING = "CONNECTING"
    UP = "UP"
    DOWN = "DOWN"


async def handle_noop(message: AbstractIncomingMessage) -> None:
    """Acknowledge and drop. Message bodies are never logged (they may carry PII)."""
    async with message.process(requeue=False):
        logger.info(
            "noop message received (queue=%s, message_id=%s, bytes=%d)",
            NOOP_QUEUE,
            message.message_id,
            len(message.body),
        )


class RabbitConsumer:
    """Owns one robust connection and consumes the registered queues.

    Connection happens in a background task so HTTP stays available while the
    broker is starting; `/health` reports the consumer state.
    """

    def __init__(
        self,
        params: AmqpParams,
        *,
        prefetch: int,
        reconnect_seconds: float,
        handlers: dict[str, MessageHandler] | None = None,
        dead_letter_queues: dict[str, str] | None = None,
    ) -> None:
        self._params = params
        self._prefetch = prefetch
        self._reconnect_seconds = reconnect_seconds
        self._handlers = handlers if handlers is not None else {NOOP_QUEUE: handle_noop}
        # queue name -> its dead-letter queue. core-api declares these queues with the same
        # arguments and RabbitMQ rejects a redeclaration that differs, so they must match exactly.
        self._dead_letter_queues = dead_letter_queues or {}
        self._connection: AbstractRobustConnection | None = None
        self._task: asyncio.Task[None] | None = None
        self._state = ConsumerState.CONNECTING

    @property
    def state(self) -> ConsumerState:
        if self._state is ConsumerState.UP and (
            self._connection is None or self._connection.is_closed
        ):
            return ConsumerState.DOWN
        return self._state

    def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="rabbit-consumer")

    async def _run(self) -> None:
        while True:
            try:
                self._connection = await aio_pika.connect_robust(
                    host=self._params.host,
                    port=self._params.port,
                    login=self._params.login,
                    password=self._params.password,
                    virtualhost=self._params.virtualhost,
                )
                channel = await self._connection.channel()
                await channel.set_qos(prefetch_count=self._prefetch)
                for queue_name, handler in self._handlers.items():
                    arguments: dict[str, str] | None = None
                    dead_letter = self._dead_letter_queues.get(queue_name)
                    if dead_letter is not None:
                        await channel.declare_queue(dead_letter, durable=True)
                        arguments = {
                            "x-dead-letter-exchange": "",
                            "x-dead-letter-routing-key": dead_letter,
                        }
                    queue = await channel.declare_queue(
                        queue_name, durable=True, arguments=arguments
                    )
                    await queue.consume(handler)
                self._state = ConsumerState.UP
                logger.info("RabbitMQ consumer started (queues=%s)", ", ".join(self._handlers))
                return  # connect_robust handles reconnects from here on
            except (aio_pika.exceptions.AMQPError, OSError) as e:
                self._state = ConsumerState.DOWN
                logger.warning(
                    "RabbitMQ connection failed (%s); retrying in %.0fs",
                    type(e).__name__,
                    self._reconnect_seconds,
                )
                if self._connection is not None:
                    await self._connection.close()
                    self._connection = None
                await asyncio.sleep(self._reconnect_seconds)

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
        if self._connection is not None:
            await self._connection.close()
