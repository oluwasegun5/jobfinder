"""POST /v1/cover-letter, /v1/screening-answers, /v1/follow-up-email and /v1/fact-check-text
(docs/adr/0031-cover-letters-and-application-pack.md, docs/adr/0032-application-tracker.md).

The first three make one strong-model call each and return prose with the fact check of that prose.
`/fact-check-text` is the fact check alone, for prose only: deterministic, no model, no usage;
core-api calls it again after the user edits a letter or an answer and before it approves one.
"""

from datetime import date
from typing import Annotated

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field

from app.api.deps import LLMProviderDep, SettingsDep
from app.api.schemas import UsageRecord
from app.factcheck import FactCheckResult, check_texts
from app.parsing.schema import Contact, ParsedResume
from app.writing.common import Length, Tone
from app.writing.follow_up import FollowUpRequest, write_follow_up
from app.writing.letter import CoverLetterRequest, Letter, write_cover_letter
from app.writing.screening import Answer, ScreeningRequest, answer_screening

router = APIRouter(prefix="/v1", tags=["writing"])


class CoverLetterResponse(BaseModel):
    prompt_version: str
    model: str
    tone: Tone
    length: Length
    # The user's own contact block, from their resume: never written by the model.
    sender: Contact
    letter: Letter
    fact_check: FactCheckResult
    # Instruction-like sentences removed from the job text and the notes before the model saw them.
    job_text_redactions: int
    notes_redactions: int
    usage: list[UsageRecord]


@router.post("/cover-letter", response_model=CoverLetterResponse)
async def cover_letter_route(
    body: CoverLetterRequest, provider: LLMProviderDep, settings: SettingsDep
) -> CoverLetterResponse:
    outcome = await write_cover_letter(provider, settings, body)
    return CoverLetterResponse(
        prompt_version=outcome.prompt_version,
        model=outcome.model,
        tone=outcome.tone,
        length=outcome.length,
        sender=outcome.sender,
        letter=outcome.letter,
        fact_check=outcome.fact_check,
        job_text_redactions=outcome.job_text_redactions,
        notes_redactions=outcome.notes_redactions,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )


class ScreeningAnswersResponse(BaseModel):
    prompt_version: str
    model: str
    tone: Tone
    length: Length
    # The whole catalogue, in order: GENERATED (the model, fact-checked), FROM_PROFILE (code) or
    # NEEDS_INPUT (the profile lacks the fact; empty text and a hint).
    answers: list[Answer]
    fact_check: FactCheckResult
    job_text_redactions: int
    notes_redactions: int
    usage: list[UsageRecord]


@router.post("/screening-answers", response_model=ScreeningAnswersResponse)
async def screening_answers_route(
    body: ScreeningRequest, provider: LLMProviderDep, settings: SettingsDep
) -> ScreeningAnswersResponse:
    outcome = await answer_screening(provider, settings, body)
    return ScreeningAnswersResponse(
        prompt_version=outcome.prompt_version,
        model=outcome.model,
        tone=outcome.tone,
        length=outcome.length,
        answers=outcome.answers,
        fact_check=outcome.fact_check,
        job_text_redactions=outcome.job_text_redactions,
        notes_redactions=outcome.notes_redactions,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )


class FollowUpResponse(BaseModel):
    prompt_version: str
    model: str
    tone: Tone
    length: Length
    subject: str
    # Salutation, paragraphs, closing and the candidate's name from the resume, as plain text.
    body: str
    fact_check: FactCheckResult
    job_text_redactions: int
    notes_redactions: int
    usage: list[UsageRecord]


@router.post("/follow-up-email", response_model=FollowUpResponse)
async def follow_up_email_route(
    body: FollowUpRequest, provider: LLMProviderDep, settings: SettingsDep
) -> FollowUpResponse:
    outcome = await write_follow_up(provider, settings, body)
    return FollowUpResponse(
        prompt_version=outcome.prompt_version,
        model=outcome.model,
        tone=outcome.tone,
        length=outcome.length,
        subject=outcome.subject,
        body=outcome.body,
        fact_check=outcome.fact_check,
        job_text_redactions=outcome.job_text_redactions,
        notes_redactions=outcome.notes_redactions,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )


class TextItem(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    path: Annotated[str, Field(min_length=1, max_length=80)]
    text: Annotated[str, Field(max_length=4000)]


class FactCheckTextRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    source: ParsedResume
    texts: list[TextItem] = Field(min_length=1, max_length=40)
    job_description: Annotated[str | None, Field(max_length=60000)] = None
    # Text the writer may use besides the resume: the job's title and company, the user's notes.
    allowed_context: Annotated[str, Field(max_length=4000)] = ""
    years_experience: Annotated[int | None, Field(ge=0, le=60)] = None
    as_of: date | None = None


@router.post("/fact-check-text", response_model=FactCheckResult)
async def fact_check_text_route(body: FactCheckTextRequest) -> FactCheckResult:
    return check_texts(
        body.source,
        [(t.path, t.text) for t in body.texts],
        body.job_description,
        allowed_context=body.allowed_context,
        years_experience=body.years_experience,
        as_of=body.as_of,
    )
