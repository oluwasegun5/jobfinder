"""Cover letters (docs/adr/0031-cover-letters-and-application-pack.md): POST /v1/cover-letter.

The pipeline is: scrub the untrusted job text and the user's notes, one strong-model call that
returns only the prose (a salutation, plain-text paragraphs, a closing), finalize in code, then the
deterministic fact check of the prose against the resume. Code, not the model, puts the user's own
contact details on the letter and signs it; the salutation and the closing must be one of a few
safe forms or they are replaced by the tone's default; the length option is enforced by trimming.
"""

import json
import logging
import secrets
from dataclasses import dataclass, field
from datetime import date
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field

from app.config import Settings
from app.factcheck import FactCheckResult, check_texts
from app.factcheck.injection import is_instruction_like, scrub_job_text
from app.llm.base import LLMProvider, LLMRequest, LLMUsage, ModelTier
from app.llm.structured import generate_structured
from app.matching.service import UnknownPromptVersionError
from app.parsing.schema import Contact, ParsedResume
from app.prompts import Prompt, load_prompt
from app.tailoring.schema import TailorJob
from app.writing.common import (
    Length,
    PlainText,
    Tone,
    block,
    block_re,
    clean_notes,
    defuse,
    job_json,
    resume_payload,
    trim_sentences,
    with_redaction_flag,
    word_count,
)

logger = logging.getLogger(__name__)

FEATURE = "cover_letter"
# Most words a letter may have, by length option; paragraphs are dropped from the end beyond it.
MAX_WORDS = {Length.SHORT: 200, Length.STANDARD: 400, Length.LONG: 600}

SALUTATION_DEFAULT = {
    Tone.FORMAL: "Dear Hiring Manager,",
    Tone.WARM: "Hello hiring team,",
    Tone.CONCISE: "Dear Hiring Team,",
}
CLOSING_DEFAULT = {
    Tone.FORMAL: "Yours sincerely,",
    Tone.WARM: "Warm regards,",
    Tone.CONCISE: "Best regards,",
}
_CLOSINGS = frozenset(
    {
        "sincerely",
        "yours sincerely",
        "yours faithfully",
        "best regards",
        "warm regards",
        "kind regards",
        "regards",
        "best wishes",
        "with thanks",
        "thank you",
        "many thanks",
    }
)


def resolve_prompt(version: str) -> Prompt:
    try:
        return load_prompt(FEATURE, int(version.rsplit("/v", 1)[1]))
    except FileNotFoundError as e:
        raise UnknownPromptVersionError(version) from e


class CoverLetterRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    user_id: UUID
    prompt_version: str = Field(
        default="cover_letter/v1", pattern=r"^cover_letter/v[1-9][0-9]{0,2}$"
    )
    resume: ParsedResume
    job: TailorJob
    tone: Tone = Tone.FORMAL
    length: Length = Length.STANDARD
    # What the user wants said or stressed. Untrusted-ish: limited and scrubbed like the posting.
    notes: Annotated[str | None, BeforeValidator(clean_notes), Field(max_length=1000)] = None
    # The profile's years of experience, if set: one of the facts a "N years" claim may rest on.
    years_experience: int | None = Field(default=None, ge=0, le=60)
    as_of: date | None = None


class LlmCoverLetter(BaseModel):
    """What the model must return: prose only. No contact details, no name, no fact check."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    salutation: Annotated[PlainText, Field(max_length=120)]
    paragraphs: list[Annotated[PlainText, Field(min_length=20, max_length=1600)]] = Field(
        min_length=1, max_length=8
    )
    closing: Annotated[PlainText, Field(max_length=60)]


class Letter(BaseModel):
    model_config = ConfigDict(frozen=True)

    salutation: str
    paragraphs: list[str]
    closing: str
    # The candidate's own name from their resume, put there by code; null if the resume has none.
    signature: str | None


def build_user_message(
    resume: ParsedResume,
    job: TailorJob,
    description: str,
    notes: str | None,
    tone: Tone,
    length: Length,
    nonce: str,
) -> str:
    parts = [
        block("RESUME", nonce, resume_payload(resume)),
        block("JOB", nonce, job_json(job.title, job.company, description)),
    ]
    if notes:
        parts.append(block("NOTES", nonce, defuse(json.dumps(notes, ensure_ascii=False))))
    options = json.dumps(
        {"tone": tone.value, "length": length.value, "max_words": MAX_WORDS[length]}
    )
    return (
        "\n\n".join(parts)
        + "\n\nWrite the cover letter described in your instructions for the candidate and the job "
        "above, using only the resume's facts. " + f"Options: {options}"
    )


def parse_message(
    message: str,
) -> tuple[ParsedResume, dict[str, str], str | None, Tone, Length]:
    """Reads the blocks back out of a message built by `build_user_message` (fake provider)."""
    resume = block_re("RESUME").search(message)
    job = block_re("JOB").search(message)
    if resume is None or job is None:
        raise ValueError("no resume or job block in the message")
    notes = block_re("NOTES").search(message)
    options = json.loads(message.rsplit("Options: ", 1)[1])
    return (
        ParsedResume.model_validate_json(resume.group(2)),
        json.loads(job.group(2)),
        json.loads(notes.group(2)) if notes else None,
        Tone(options["tone"]),
        Length(options["length"]),
    )


@dataclass(slots=True)
class LetterOutcome:
    prompt_version: str
    model: str
    tone: Tone
    length: Length
    sender: Contact
    letter: Letter
    fact_check: FactCheckResult
    job_text_redactions: int
    notes_redactions: int
    usage: list[LLMUsage] = field(default_factory=list)


def _salutation(raw: str, company: str | None, tone: Tone) -> str:
    key = raw.strip().rstrip(",:").strip().casefold()
    allowed = {
        "dear hiring manager",
        "dear hiring team",
        "dear recruiting team",
        "hello hiring team",
    }
    if company and len(company) <= 60 and not is_instruction_like(company):
        name = " ".join(company.split()).casefold()
        allowed |= {
            f"dear {name} hiring team",
            f"dear {name} team",
            f"hello {name} team",
            f"hello {name} hiring team",
        }
    if key in allowed:
        return " ".join(raw.strip().rstrip(",:").split()) + ","
    return SALUTATION_DEFAULT[tone]


def _closing(raw: str, tone: Tone) -> str:
    key = raw.strip().rstrip(",:.").strip().casefold()
    if key in _CLOSINGS:
        return raw.strip().rstrip(",:.").strip() + ","
    return CLOSING_DEFAULT[tone]


def finalize(generated: LlmCoverLetter, request: CoverLetterRequest) -> tuple[Letter, Contact]:
    """Guardrails code can enforce: salutation, closing, the length budget, the signature."""
    budget = MAX_WORDS[request.length]
    paragraphs = list(generated.paragraphs)
    while len(paragraphs) > 1 and sum(word_count(p) for p in paragraphs) > budget:
        paragraphs.pop()
    if sum(word_count(p) for p in paragraphs) > budget:
        paragraphs = [trim_sentences(paragraphs[0], budget)]
    contact = request.resume.contact
    letter = Letter(
        salutation=_salutation(generated.salutation, request.job.company, request.tone),
        paragraphs=paragraphs,
        closing=_closing(generated.closing, request.tone),
        signature=contact.full_name,
    )
    return letter, contact


async def write_cover_letter(
    provider: LLMProvider, settings: Settings, request: CoverLetterRequest
) -> LetterOutcome:
    prompt = resolve_prompt(request.prompt_version)
    raw_description = request.job.description or ""
    description, removed = scrub_job_text(raw_description, settings.tailor_description_chars)
    notes, notes_removed = (
        scrub_job_text(request.notes, settings.writing_notes_chars) if request.notes else ("", [])
    )
    if removed or notes_removed:
        logger.warning(
            "Removed %d instruction-like sentence(s) from a job description and %d from notes",
            len(removed),
            len(notes_removed),
        )
    llm_request = LLMRequest(
        user_id=request.user_id,
        feature=FEATURE,
        prompt_version=prompt.prompt_version,
        tier=ModelTier.STRONG,
        system=prompt.text,
        user_message=build_user_message(
            request.resume,
            request.job,
            description,
            notes or None,
            request.tone,
            request.length,
            secrets.token_hex(8),
        ),
        max_tokens=settings.cover_letter_max_tokens,
    )
    generated = await generate_structured(provider, llm_request, LlmCoverLetter)
    letter, sender = finalize(generated.value, request)
    # Facts the writer may use besides the resume: the job's title and company, the user's notes.
    context = " ".join(x for x in (request.job.title, request.job.company, notes) if x)
    fact = check_texts(
        request.resume,
        [(f"paragraphs[{i}]", p) for i, p in enumerate(letter.paragraphs)],
        raw_description or None,
        allowed_context=context,
        years_experience=request.years_experience,
        as_of=request.as_of,
    )
    fact = with_redaction_flag(
        fact,
        removed,
        "job.description",
        "The job posting contained instructions to an AI, which were removed and ignored.",
    )
    fact = with_redaction_flag(
        fact,
        notes_removed,
        "notes",
        "Your notes contained instructions to an AI, which were ignored.",
    )
    return LetterOutcome(
        prompt_version=prompt.prompt_version,
        model=generated.usage[-1].model,
        tone=request.tone,
        length=request.length,
        sender=sender,
        letter=letter,
        fact_check=fact,
        job_text_redactions=len(removed),
        notes_redactions=len(notes_removed),
        usage=generated.usage,
    )
