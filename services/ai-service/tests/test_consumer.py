import asyncio
from types import TracebackType

from app.workers.consumer import AmqpParams, ConsumerState, RabbitConsumer, handle_noop


class _FakeMessage:
    message_id = "m-1"
    body = b'{"anything": true}'

    def __init__(self) -> None:
        self.acked = False
        self.rejected = False

    def process(self, requeue: bool = False) -> "_FakeMessage":
        return self

    async def __aenter__(self) -> None:
        return None

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        if exc is None:
            self.acked = True
        else:
            self.rejected = True


async def test_noop_handler_acks_message() -> None:
    message = _FakeMessage()
    await handle_noop(message)  # type: ignore[arg-type]
    assert message.acked
    assert not message.rejected


async def test_consumer_reports_down_when_broker_unreachable() -> None:
    consumer = RabbitConsumer(
        AmqpParams(host="127.0.0.1", port=1, login="guest", password="guest"),
        prefetch=1,
        reconnect_seconds=0.05,
    )
    consumer.start()
    try:
        for _ in range(100):
            if consumer.state is ConsumerState.DOWN:
                break
            await asyncio.sleep(0.02)
        assert consumer.state is ConsumerState.DOWN
    finally:
        await consumer.stop()
