import asyncio

from app.embeddings.batching import BatchCollector


class _Message:
    def __init__(self, name: str) -> None:
        self.name = name
        self.rejected = False

    async def reject(self, requeue: bool = False) -> None:
        self.rejected = True


async def test_a_full_batch_is_handled_at_once_and_the_rest_waits_for_the_timer() -> None:
    batches: list[list[str]] = []

    async def handler(messages: list[_Message]) -> None:
        batches.append([m.name for m in messages])

    collector = BatchCollector(handler, batch_size=3, max_wait_seconds=0.05)
    for name in "abcde":
        await collector(_Message(name))

    assert batches == [["a", "b", "c"]]
    await asyncio.sleep(0.2)
    assert batches == [["a", "b", "c"], ["d", "e"]]
    await collector.aclose()


async def test_a_lone_message_is_handled_after_the_wait() -> None:
    batches: list[list[str]] = []

    async def handler(messages: list[_Message]) -> None:
        batches.append([m.name for m in messages])

    collector = BatchCollector(handler, batch_size=10, max_wait_seconds=0.05)
    await collector(_Message("only"))
    assert batches == []
    await asyncio.sleep(0.2)
    assert batches == [["only"]]
    await collector.aclose()


async def test_a_handler_that_blows_up_rejects_its_messages_instead_of_losing_them() -> None:
    async def handler(messages: list[_Message]) -> None:
        raise RuntimeError("bug")

    collector = BatchCollector(handler, batch_size=2, max_wait_seconds=1)
    first, second = _Message("a"), _Message("b")
    await collector(first)
    await collector(second)

    assert first.rejected
    assert second.rejected
    await collector.aclose()
