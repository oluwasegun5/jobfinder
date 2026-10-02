"""An overall deadline for one request, with the usage of the calls that finished before it ran out.

`generate_structured` and the services keep their usage lists in local variables, so cancelling
them at the deadline would lose the record of calls that were already billed.
`RecordingProvider` therefore notes every completed call (and the usage a provider reports on an
error, such as a refusal) as it happens, and `run_with_deadline` hands that list to the error it
raises. The deadline cancels the awaiting task, which cancels the provider call in flight; a call
cancelled that way has no usage to report.
"""

import asyncio
from collections.abc import Awaitable, Callable

from app.llm.base import LLMDeadlineError, LLMError, LLMProvider, LLMRequest, LLMResponse, LLMUsage


class RecordingProvider:
    """Wraps a provider and keeps the usage of every call that completed through it."""

    def __init__(self, inner: LLMProvider) -> None:
        self._inner = inner
        self.name = inner.name
        self.usage: list[LLMUsage] = []

    async def generate(self, request: LLMRequest) -> LLMResponse:
        try:
            response = await self._inner.generate(request)
        except LLMError as e:
            self.usage.extend(e.usage)
            raise
        self.usage.append(response.usage)
        return response

    async def aclose(self) -> None:
        await self._inner.aclose()


async def run_with_deadline[T](
    provider: LLMProvider,
    seconds: float,
    operation: Callable[[LLMProvider], Awaitable[T]],
) -> T:
    """Runs `operation(provider)` for at most `seconds`.

    On timeout the operation is cancelled (so is any provider call it is awaiting) and
    `LLMDeadlineError` is raised with the usage of the calls that had completed. Any other error
    passes through unchanged.
    """
    recorder = RecordingProvider(provider)
    try:
        async with asyncio.timeout(seconds):
            return await operation(recorder)
    except TimeoutError as e:
        # Nothing below raises a TimeoutError of its own: providers translate their own timeouts
        # into LLMProviderError, so this is the deadline.
        error = LLMDeadlineError(f"Deadline of {seconds:g}s exceeded")
        error.usage = list(recorder.usage)
        raise error from e
