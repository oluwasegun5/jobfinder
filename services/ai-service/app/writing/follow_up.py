"""Follow-up emails (docs/adr/0032-application-tracker.md): POST /v1/follow-up-email.

The pipeline is the cover letter's: scrub the untrusted job text and the user's notes, one
strong-model call that returns only the subject and the prose, finalize in code, then the
deterministic fact check of the prose against the resume. Code, not the model, writes the
salutation and the closing and signs with the candidate's own name from the resume; the length
option is enforced by trimming. The only facts the model gets about the application are the ones
core-api sends (title, company, status, how many days ago it was applied): it is never told, and
may never claim, who it is writing to or what was said.
"""

import json
import logging
import secrets
from dataclasses import dataclass, field
from datetime import date
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field

from app.config import Settings
from app.factcheck import FactCheckResult, check_texts
from app.factcheck.injection import REDACTION, is_instruction_like, scrub_job_text
from app.llm.base import LLMProvider, LLMRequest, LLMUsage, ModelTier
from app.llm.structured import generate_structured
from app.matching.service import UnknownPromptVersionError
from app.parsing.schema import ParsedResume
from app.prompts import Prompt, load_prompt
from app.tailoring.schema import Line, OptLine, Posting
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
from app.writing.letter import CLOSING_DEFAULT

logger = logging.getLogger(__name__)

FEATURE = "follow_up_email"
# Most words the paragraphs may have in total, by length option; paragraphs go from the end.
MAX_WORDS = {Length.SHORT: 80, Length.STANDARD: 150, Length.LONG: 250}
SALUTATION = {
    Tone.FORMAL: "Dear Hiring Manager,",
    Tone.WARM: "Hello hiring team,",
    Tone.CONCISE: "Hello,",
}

Status = Literal["SAVED", "APPLIED", "SCREENING", "INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"]


def resolve_prompt(version: str) -> Prompt:
    try:
        return load_prompt(FEATURE, int(version.rsplit("/v", 1)[1]))
    except FileNotFoundError as e:
        raise UnknownPromptVersionError(version) from e


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class ApplicationFacts(_Strict):
    """What the tracker knows about the application. The title and company come from the posting
    (or were typed by the user), so they are untrusted like the posting."""

    title: Line
    company: OptLine = None
    status: Status
    applied_on: date | None = None


class FollowUpRequest(_Strict):
    user_id: UUID
    prompt_version: str = Field(
        default="follow_up_email/v1", pattern=r"^follow_up_email/v[1-9][0-9]{0,2}$"
    )
    resume: ParsedResume
    application: ApplicationFacts
    # The posting the application was made for, when the application came from a job we know.
    job_description: Posting = None
    tone: Tone = Tone.FORMAL
    length: Length = Length.SHORT
    # What the user wants said (for example availability). Scrubbed like the posting.
    notes: Annotated[str | None, BeforeValidator(clean_notes), Field(max_length=1000)] = None
    years_experience: int | None = Field(default=None, ge=0, le=60)
    as_of: date | None = None


class LlmFollowUp(_Strict):
    """What the model must return: a subject and prose. No names, contact details or fact check."""

    subject: Annotated[PlainText, Field(max_length=120)]
    paragraphs: list[Annotated[PlainText, Field(min_length=20, max_length=1200)]] = Field(
        min_length=1, max_length=4
    )


def days_since(applied_on: date | None, as_of: date) -> int | None:
    if applied_on is None or applied_on > as_of:
        return None
    return (as_of - applied_on).days


def build_user_message(
    request: FollowUpRequest,
    description: str,
    notes: str | None,
    as_of: date,
    nonce: str,
) -> str:
    app = request.application
    facts: dict[str, object] = {
        "title": REDACTION if is_instruction_like(app.title) else app.title,
        "status": app.status,
    }
    if app.company:
        facts["company"] = REDACTION if is_instruction_like(app.company) else app.company
    days = days_since(app.applied_on, as_of)
    if days is not None:
        facts["days_since_applied"] = days
    parts = [
        block("RESUME", nonce, resume_payload(request.resume)),
        block("APPLICATION", nonce, defuse(json.dumps(facts, ensure_ascii=False))),
    ]
    if description:
        parts.append(block("JOB", nonce, job_json(app.title, app.company, description)))
    if notes:
        parts.append(block("NOTES", nonce, defuse(json.dumps(notes, ensure_ascii=False))))
    options = json.dumps(
        {
            "tone": request.tone.value,
            "length": request.length.value,
            "max_words": MAX_WORDS[request.length],
        }
    )
    return (
        "\n\n".join(parts)
        + "\n\nWrite the follow-up email described in your instructions for the application "
        "above, using only the resume's and the application's facts. " + f"Options: {options}"
    )


def parse_message(
    message: str,
) -> tuple[ParsedResume, dict[str, object], dict[str, str] | None, str | None, Tone, Length]:
    """Reads the blocks back out of a message built by `build_user_message` (fake provider)."""
    resume = block_re("RESUME").search(message)
    application = block_re("APPLICATION").search(message)
    if resume is None or application is None:
        raise ValueError("no resume or application block in the message")
    job = block_re("JOB").search(message)
    notes = block_re("NOTES").search(message)
    options = json.loads(message.rsplit("Options: ", 1)[1])
    return (
        ParsedResume.model_validate_json(resume.group(2)),
        json.loads(application.group(2)),
        json.loads(job.group(2)) if job else None,
        json.loads(notes.group(2)) if notes else None,
        Tone(options["tone"]),
        Length(options["length"]),
    )


@dataclass(slots=True)
class FollowUpOutcome:
    prompt_version: str
    model: str
    tone: Tone
    length: Length
    subject: str
    body: str
    fact_check: FactCheckResult
    job_text_redactions: int
    notes_redactions: int
    usage: list[LLMUsage] = field(default_factory=list)


def finalize(generated: LlmFollowUp, request: FollowUpRequest) -> tuple[str, list[str], str]:
    """Guardrails code can enforce: the length budget, the salutation, the closing, the signature.

    Returns the subject, the paragraphs that were kept and the whole body.
    """
    budget = MAX_WORDS[request.length]
    paragraphs = list(generated.paragraphs)
    while len(paragraphs) > 1 and sum(word_count(p) for p in paragraphs) > budget:
        paragraphs.pop()
    if sum(word_count(p) for p in paragraphs) > budget:
        paragraphs = [trim_sentences(paragraphs[0], budget)]
    lines = [
        SALUTATION[request.tone],
        "",
        "\n\n".join(paragraphs),
        "",
        CLOSING_DEFAULT[request.tone],
    ]
    if request.resume.contact.full_name:
        lines.append(request.resume.contact.full_name)
    return generated.subject, paragraphs, "\n".join(lines)


async def write_follow_up(
    provider: LLMProvider, settings: Settings, request: FollowUpRequest
) -> FollowUpOutcome:
    prompt = resolve_prompt(request.prompt_version)
    as_of = request.as_of or date.today()
    raw_description = request.job_description or ""
    description, removed = (
        scrub_job_text(raw_description, settings.tailor_description_chars)
        if raw_description
        else ("", [])
    )
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
            request, description, notes or None, as_of, secrets.token_hex(8)
        ),
        max_tokens=settings.follow_up_email_max_tokens,
    )
    generated = await generate_structured(provider, llm_request, LlmFollowUp)
    subject, paragraphs, body = finalize(generated.value, request)
    app = request.application
    days = days_since(app.applied_on, as_of)
    # Facts the writer may use besides the resume: the application's own title, company and age, and
    # the user's notes.
    context = " ".join(
        x
        for x in (
            app.title,
            app.company,
            f"{days} days" if days is not None else None,
            notes,
        )
        if x
    )
    fact = check_texts(
        request.resume,
        [("subject", subject)] + [(f"paragraphs[{i}]", p) for i, p in enumerate(paragraphs)],
        raw_description or None,
        allowed_context=context,
        years_experience=request.years_experience,
        as_of=as_of,
    )
    fact = with_redaction_flag(
        fact,
        removed,
        "job_description",
        "The job posting contained instructions to an AI, which were removed and ignored.",
    )
    fact = with_redaction_flag(
        fact,
        notes_removed,
        "notes",
        "Your notes contained instructions to an AI, which were ignored.",
    )
    return FollowUpOutcome(
        prompt_version=prompt.prompt_version,
        model=generated.usage[-1].model,
        tone=request.tone,
        length=request.length,
        subject=subject,
        body=body,
        fact_check=fact,
        job_text_redactions=len(removed),
        notes_redactions=len(notes_removed),
        usage=generated.usage,
    )
