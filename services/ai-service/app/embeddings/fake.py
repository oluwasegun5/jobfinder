import hashlib
import math
import struct
from collections.abc import Sequence

from app.embeddings.base import EmbeddingBatch, EmbeddingInputType


class FakeEmbeddingProvider:
    """Deterministic, keyless embeddings for tests and local development.

    The vector is derived from a SHA-256 stream of the text and the input type, then scaled to unit
    length, so equal texts always get equal vectors and different texts almost surely do not. It
    carries no meaning (similar texts are NOT close), so it proves the plumbing, never the matching.
    Its model name must start with `fake-`, so these vectors are never mistaken for a real model's.
    """

    name = "fake"

    def __init__(self, *, model: str, dimension: int) -> None:
        if not model.startswith("fake-"):
            raise ValueError("the fake embedding model name must start with 'fake-'")
        self.model = model
        self.dimension = dimension
        self.calls: list[tuple[list[str], EmbeddingInputType]] = []

    async def embed(self, texts: Sequence[str], input_type: EmbeddingInputType) -> EmbeddingBatch:
        self.calls.append((list(texts), input_type))
        return EmbeddingBatch(
            vectors=[self._vector(text, input_type) for text in texts],
            model=self.model,
            input_tokens=sum(len(text.split()) for text in texts),
            latency_ms=0,
        )

    def _vector(self, text: str, input_type: EmbeddingInputType) -> list[float]:
        seed = f"{self.model}|{input_type.value}|{text}".encode()
        values: list[float] = []
        counter = 0
        while len(values) < self.dimension:
            block = hashlib.sha256(seed + counter.to_bytes(4, "big")).digest()
            values.extend(
                (struct.unpack(">I", block[i : i + 4])[0] / 2**31) - 1.0 for i in range(0, 32, 4)
            )
            counter += 1
        values = values[: self.dimension]
        norm = math.sqrt(sum(v * v for v in values)) or 1.0
        return [v / norm for v in values]

    async def aclose(self) -> None:
        return None
