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


@lru_cache
def load_named_prompt(feature: str, name: str, version: int) -> Prompt:
    """A feature with several prompts (interview prep: questions and brief) keeps one file each,
    `app/prompts/<feature>/<name>_v<n>.md`; they share the feature's version number, so
    `interview/v1` means `questions_v1.md` and `brief_v1.md` together."""
    if not _NAME.match(feature) or not _NAME.match(name) or version < 1:
        raise ValueError(f"Invalid prompt reference {feature!r} {name!r} v{version}")
    path = _PROMPTS_DIR / feature / f"{name}_v{version}.md"
    return Prompt(feature=feature, version=version, text=path.read_text(encoding="utf-8").strip())
