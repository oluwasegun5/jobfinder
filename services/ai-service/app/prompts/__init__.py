"""Versioned prompts in app/prompts/<feature>/v<n>.md. Changing a prompt means adding a version."""

import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

_PROMPTS_DIR = Path(__file__).parent
_NAME = re.compile(r"^[a-z0-9_]+$")


@dataclass(frozen=True, slots=True)
class Prompt:
    feature: str
    version: int
    text: str

    @property
    def prompt_version(self) -> str:
        """Identifier stored alongside every AI output, e.g. ``diagnostics/v1``."""
        return f"{self.feature}/v{self.version}"


@lru_cache
def load_prompt(feature: str, version: int) -> Prompt:
    if not _NAME.match(feature) or version < 1:
        raise ValueError(f"Invalid prompt reference {feature!r} v{version}")
    path = _PROMPTS_DIR / feature / f"v{version}.md"
    return Prompt(feature=feature, version=version, text=path.read_text(encoding="utf-8").strip())
