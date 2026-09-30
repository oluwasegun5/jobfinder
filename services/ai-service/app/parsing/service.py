"""CV parsing: text extraction, then an LLM call validated against `ParsedResume`."""

import asyncio
import secrets
from dataclasses import dataclass
from uuid import UUID

from app.llm import LLMProvider, LLMRequest, LLMUsage, ModelTier, generate_structured
from app.parsing.extract import extract_text
from app.parsing.grounding import ParseWarning, check_grounding
from app.parsing.schema import ParsedResume
from app.prompts import load_prompt

FEATURE = "parse_resume"
PROMPT = load_prompt(FEATURE, 1)

# A dense two-page CV is ~2-3k tokens of JSON; leave room for the 5-page worst case.
_MAX_OUTPUT_TOKENS = 8192
_MARKER = "<<<CV_TEXT_"


@dataclass(frozen=True, slots=True)
class ParseOutcome:
    resume: ParsedResume
    warnings: list[ParseWarning]
    # One entry per LLM call (a validation retry is billed too).
    usage: list[LLMUsage]
    prompt_version: str


def build_user_message(text: str, nonce: str) -> str:
    """The CV goes into a data block whose markers carry a random token the CV cannot know.

    Any marker-lookalike inside the text is defused, so the document cannot close its own block
    and pose as instructions. The only trusted instruction sits after the block.
    """
    safe = text.replace(_MARKER, "<<< CV_TEXT_")
    return (
        f"{_MARKER}BEGIN {nonce}>>>\n{safe}\n{_MARKER}END {nonce}>>>\n\n"
        "Extract the CV above into the JSON object described in your instructions."
    )


async def parse_resume(provider: LLMProvider, user_id: UUID, data: bytes) -> ParseOutcome:
    text = await asyncio.to_thread(extract_text, data)
    request = LLMRequest(
        user_id=user_id,
        feature=FEATURE,
        prompt_version=PROMPT.prompt_version,
        tier=ModelTier.FAST,
        system=PROMPT.text,
        user_message=build_user_message(text, secrets.token_hex(8)),
        max_tokens=_MAX_OUTPUT_TOKENS,
    )
    result = await generate_structured(provider, request, ParsedResume)
    resume, warnings = check_grounding(result.value, text)
    return ParseOutcome(
        resume=resume,
        warnings=warnings,
        usage=result.usage,
        prompt_version=PROMPT.prompt_version,
    )
