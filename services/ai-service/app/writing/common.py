"""Shared pieces of the two writing features, cover letters and screening answers (docs/adr/0031).

Both treat the job posting, the user's notes and the resume as untrusted data in delimited blocks
(the same scrubbing and delimiting as tailoring), both validate the model's prose strictly (plain
text, no markdown, no placeholders), and both end in the deterministic fact check of the prose.
"""

import json
import re
from enum import StrEnum
from typing import Annotated

from pydantic import AfterValidator, BeforeValidator, Field

from app.factcheck import PLACEHOLDER_RE, FactCheckResult, FactFlag, FlagCode
from app.factcheck.checker import SEVERITY
from app.factcheck.injection import REDACTION, is_instruction_like
from app.parsing.schema import ParsedResume

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")
_SPACES = re.compile(r"\s+")
_MARKDOWN = re.compile(r"\*\*|__|`|\]\(|~~|^\s*(?:[-*+>#]|\d+[.)])\s|(?:^|\s)#{1,6}\s")
_EDGE_EMPHASIS = re.compile(r"^\s*[*_]|[*_]\s*$")


class Tone(StrEnum):
    FORMAL = "formal"
    WARM = "warm"
    CONCISE = "concise"


class Length(StrEnum):
    SHORT = "short"
    STANDARD = "standard"
    LONG = "long"


def _plain(value: object) -> object:
    """Whitespace (including line breaks inside a paragraph) becomes single spaces."""
    if isinstance(value, str):
        return _SPACES.sub(" ", _CONTROL_CHARS.sub(" ", value)).strip()
    return value


def _reject_unplain(value: str) -> str:
    if PLACEHOLDER_RE.search(value):
        raise ValueError("contains a placeholder")
    if REDACTION in value:
        raise ValueError("contains a redaction marker")
    if _MARKDOWN.search(value) or _EDGE_EMPHASIS.search(value):
        raise ValueError("contains markdown")
    return value


# One paragraph or one answer of plain prose: no markdown, no placeholder, no line breaks.
PlainText = Annotated[
    str, BeforeValidator(_plain), AfterValidator(_reject_unplain), Field(min_length=1)
]


def clean_notes(value: object) -> object:
    cleaned = _plain(value)
    return None if cleaned == "" else cleaned


def defuse(text: str) -> str:
    return text.replace("<<<", "< < <").replace(">>>", "> > >")


def block(name: str, nonce: str, payload: str) -> str:
    return f"<<<{name}_BEGIN {nonce}>>>\n{payload}\n<<<{name}_END {nonce}>>>"


def block_re(name: str) -> re.Pattern[str]:
    return re.compile(rf"<<<{name}_BEGIN (\w+)>>>\n(.*?)\n<<<{name}_END \1>>>", re.DOTALL)


def resume_payload(resume: ParsedResume) -> str:
    """The resume without its contact block: the model has no use for it and it is personal data."""
    data = resume.model_dump(mode="json", exclude={"contact"})
    return defuse(json.dumps(data, ensure_ascii=False))


def job_json(title: str, company: str | None, description: str, **extra: object) -> str:
    """`description` went through `scrub_job_text`; title and company are checked here."""
    data: dict[str, object] = {
        "title": REDACTION if is_instruction_like(title) else title,
        "description": description,
    }
    if company:
        data["company"] = REDACTION if is_instruction_like(company) else company
    data.update(extra)
    return defuse(json.dumps(data, ensure_ascii=False))


def with_redaction_flag(
    result: FactCheckResult, removed: list[str], path: str, message: str
) -> FactCheckResult:
    """A JOB_DESCRIPTION_INJECTION warning when instruction-like text was taken out of an input."""
    if not removed:
        return result
    flag = FactFlag(
        code=FlagCode.JOB_DESCRIPTION_INJECTION,
        severity=SEVERITY[FlagCode.JOB_DESCRIPTION_INJECTION],
        path=path,
        value=" ".join(removed[0].split())[:200],
        message=message,
    )
    return result.model_copy(
        update={"flags": [*result.flags, flag], "warnings": result.warnings + 1}
    )


def word_count(text: str) -> int:
    return len(text.split())


def trim_sentences(text: str, max_words: int) -> str:
    """Whole sentences up to the budget (at least the first, cut at a word if it alone is over)."""
    if word_count(text) <= max_words:
        return text
    kept: list[str] = []
    used = 0
    for sentence in re.split(r"(?<=[.!?])\s+", text):
        n = word_count(sentence)
        if kept and used + n > max_words:
            break
        kept.append(sentence)
        used += n
    out = " ".join(kept)
    if word_count(out) > max_words:
        out = " ".join(out.split()[:max_words]).rstrip(",;:") + "."
    return out
