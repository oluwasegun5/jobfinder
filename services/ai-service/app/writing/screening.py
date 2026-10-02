"""Answers to common screening questions (docs/adr/0031-cover-letters-and-application-pack.md).

POST /v1/screening-answers covers a fixed catalogue. Two kinds of question:

* Opinion and narrative (why this role, strengths, a growth area, the biggest achievement) are
  written by the model, grounded in the resume, and fact-checked like a cover letter.
* Factual (notice period, salary, work authorization, location and work mode, years with the key
  skills, how the user heard of the job) are filled by CODE from the profile and preferences the
  caller sends. The model never sees them and cannot change them. When the profile lacks the fact,
  the answer is NOT invented: its status is NEEDS_INPUT, its text is empty and a hint says what to
  provide.
"""

import json
import logging
import secrets
from dataclasses import dataclass, field
from datetime import date
from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    field_validator,
)

from app.config import Settings
from app.factcheck import FactCheckResult, check_texts, lexicon, normalize, total_experience_months
from app.factcheck.injection import scrub_job_text
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
)

logger = logging.getLogger(__name__)

FEATURE = "screening_answers"
# Most words of one written answer, by length option.
MAX_WORDS = {Length.SHORT: 50, Length.STANDARD: 100, Length.LONG: 160}


class AnswerId(StrEnum):
    WHY_COMPANY_ROLE = "WHY_COMPANY_ROLE"
    STRENGTHS = "STRENGTHS"
    GROWTH_AREA = "GROWTH_AREA"
    BIGGEST_ACHIEVEMENT = "BIGGEST_ACHIEVEMENT"
    NOTICE_PERIOD = "NOTICE_PERIOD"
    SALARY_EXPECTATION = "SALARY_EXPECTATION"
    WORK_AUTHORIZATION = "WORK_AUTHORIZATION"
    RELOCATION_REMOTE = "RELOCATION_REMOTE"
    YEARS_KEY_SKILLS = "YEARS_KEY_SKILLS"
    HOW_HEARD = "HOW_HEARD"


# The catalogue, in the order answers are returned.
QUESTIONS: dict[AnswerId, str] = {
    AnswerId.WHY_COMPANY_ROLE: "Why do you want to work at this company and in this role?",
    AnswerId.STRENGTHS: "What are your key strengths?",
    AnswerId.GROWTH_AREA: "What is an area you want to grow in?",
    AnswerId.BIGGEST_ACHIEVEMENT: "What is your biggest professional achievement?",
    AnswerId.NOTICE_PERIOD: "What is your notice period or earliest start date?",
    AnswerId.SALARY_EXPECTATION: "What are your salary expectations?",
    AnswerId.WORK_AUTHORIZATION: "Are you authorized to work in this country, and do you need sponsorship?",  # noqa: E501
    AnswerId.RELOCATION_REMOTE: "What are your location, relocation and remote-work preferences?",
    AnswerId.YEARS_KEY_SKILLS: "How many years of experience do you have with the key skills for this role?",  # noqa: E501
    AnswerId.HOW_HEARD: "How did you hear about this role?",
}
MODEL_IDS: tuple[AnswerId, ...] = (
    AnswerId.WHY_COMPANY_ROLE,
    AnswerId.STRENGTHS,
    AnswerId.GROWTH_AREA,
    AnswerId.BIGGEST_ACHIEVEMENT,
)

AnswerStatus = Literal["GENERATED", "FROM_PROFILE", "NEEDS_INPUT"]


def resolve_prompt(version: str) -> Prompt:
    try:
        return load_prompt(FEATURE, int(version.rsplit("/v", 1)[1]))
    except FileNotFoundError as e:
        raise UnknownPromptVersionError(version) from e


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class ScreeningJob(_Strict):
    title: Line
    company: OptLine = None
    description: Posting = None
    # The skills the job lists (as ingestion extracted them); untrusted like the description.
    skills: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=60
    )


class Preferences(_Strict):
    locations: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=20
    )
    work_modes: list[Literal["REMOTE", "HYBRID", "ONSITE"]] = Field(default_factory=list)
    # Yearly minimum, in `currency`.
    min_salary: int | None = Field(default=None, ge=0, le=2_000_000_000)
    currency: str | None = Field(default=None, pattern=r"^[A-Za-z]{3}$")
    # True only when the user said they need sponsorship; absence is "not stated", never "no".
    needs_sponsorship: bool | None = None


class ProfileFacts(_Strict):
    years_experience: int | None = Field(default=None, ge=0, le=60)
    # Reserved: the profile has no notice-period field yet, so this is normally absent.
    notice_period: Annotated[str | None, Field(max_length=100)] = None
    preferences: Preferences = Field(default_factory=Preferences)


class ScreeningRequest(_Strict):
    user_id: UUID
    prompt_version: str = Field(
        default="screening_answers/v1", pattern=r"^screening_answers/v[1-9][0-9]{0,2}$"
    )
    resume: ParsedResume
    job: ScreeningJob
    profile: ProfileFacts = Field(default_factory=ProfileFacts)
    tone: Tone = Tone.FORMAL
    length: Length = Length.STANDARD
    notes: Annotated[str | None, BeforeValidator(clean_notes), Field(max_length=1000)] = None
    as_of: date | None = None


class LlmAnswer(_Strict):
    id: AnswerId
    answer: Annotated[PlainText, Field(min_length=10, max_length=1200)]


class LlmScreening(_Strict):
    answers: list[LlmAnswer] = Field(min_length=1, max_length=10)

    @field_validator("answers")
    @classmethod
    def _exactly_the_written_questions(cls, value: list[LlmAnswer]) -> list[LlmAnswer]:
        ids = [a.id for a in value]
        if sorted(ids) != sorted(MODEL_IDS):
            raise ValueError("answers must be exactly the four written questions, once each")
        return value


class Answer(BaseModel):
    model_config = ConfigDict(frozen=True)

    id: AnswerId
    question: str
    # Empty while status is NEEDS_INPUT.
    answer: str
    status: AnswerStatus
    # What the user should provide, for NEEDS_INPUT.
    hint: str | None = None


# --- what the model is shown ---


def key_skills(
    resume: ParsedResume, job: ScreeningJob, description: str
) -> tuple[list[str], list[str]]:
    """(skills the job asks for that the resume shows, skills it asks for that the resume lacks)."""
    wanted: dict[str, str] = {}
    for skill in job.skills:
        wanted.setdefault(normalize.canonical_skill(skill), skill)
    for key, written in lexicon.skills_in_text(description).items():
        wanted.setdefault(key, written)
    have = {normalize.canonical_skill(s) for s in resume.skills}
    for project in resume.projects:
        have |= {normalize.canonical_skill(t) for t in project.technologies}
    have |= set(lexicon.skills_in_text(" ".join(b for e in resume.experience for b in e.bullets)))
    matching = [w for k, w in wanted.items() if k in have][:8]
    gaps = [w for k, w in wanted.items() if k not in have][:5]
    return matching, gaps


def build_user_message(
    request: ScreeningRequest, description: str, notes: str | None, nonce: str
) -> str:
    matching, gaps = key_skills(request.resume, request.job, description)
    parts = [
        block("RESUME", nonce, resume_payload(request.resume)),
        block("JOB", nonce, job_json(request.job.title, request.job.company, description)),
        block(
            "CONTEXT",
            nonce,
            defuse(json.dumps({"matching_skills": matching, "gaps": gaps}, ensure_ascii=False)),
        ),
    ]
    if notes:
        parts.append(block("NOTES", nonce, defuse(json.dumps(notes, ensure_ascii=False))))
    options = json.dumps(
        {
            "tone": request.tone.value,
            "length": request.length.value,
            "max_words": MAX_WORDS[request.length],
            "questions": [i.value for i in MODEL_IDS],
        }
    )
    return (
        "\n\n".join(parts)
        + "\n\nAnswer the four questions described in your instructions for the candidate and the "
        "job above, using only the resume's facts. " + f"Options: {options}"
    )


def parse_message(
    message: str,
) -> tuple[ParsedResume, dict[str, str], dict[str, list[str]], str | None, Tone, Length]:
    """Reads the blocks back out of a message built by `build_user_message` (fake provider)."""
    resume = block_re("RESUME").search(message)
    job = block_re("JOB").search(message)
    context = block_re("CONTEXT").search(message)
    if resume is None or job is None or context is None:
        raise ValueError("no resume, job or context block in the message")
    notes = block_re("NOTES").search(message)
    options = json.loads(message.rsplit("Options: ", 1)[1])
    return (
        ParsedResume.model_validate_json(resume.group(2)),
        json.loads(job.group(2)),
        json.loads(context.group(2)),
        json.loads(notes.group(2)) if notes else None,
        Tone(options["tone"]),
        Length(options["length"]),
    )


# --- the facts, filled by code ---


def _join(items: list[str]) -> str:
    if len(items) <= 2:
        return " and ".join(items)
    return ", ".join(items[:-1]) + " and " + items[-1]


_MODES = {"REMOTE": "remote", "HYBRID": "hybrid", "ONSITE": "on-site"}


def _needs(answer_id: AnswerId, hint: str) -> Answer:
    return Answer(
        id=answer_id, question=QUESTIONS[answer_id], answer="", status="NEEDS_INPUT", hint=hint
    )


def _from_profile(answer_id: AnswerId, text: str) -> Answer:
    return Answer(id=answer_id, question=QUESTIONS[answer_id], answer=text, status="FROM_PROFILE")


def factual_answers(request: ScreeningRequest, matching: list[str]) -> dict[AnswerId, Answer]:
    """The factual answers, from the profile and preferences only. Missing facts stay missing."""
    profile, prefs = request.profile, request.profile.preferences
    out: dict[AnswerId, Answer] = {}

    out[AnswerId.NOTICE_PERIOD] = (
        _from_profile(AnswerId.NOTICE_PERIOD, f"My notice period is {profile.notice_period}.")
        if profile.notice_period
        else _needs(
            AnswerId.NOTICE_PERIOD, "Add your notice period or the earliest date you can start."
        )
    )

    if prefs.min_salary:
        currency = f"{prefs.currency.upper()} " if prefs.currency else ""
        out[AnswerId.SALARY_EXPECTATION] = _from_profile(
            AnswerId.SALARY_EXPECTATION,
            f"My salary expectation is at least {currency}{prefs.min_salary:,} per year.",
        )
    else:
        out[AnswerId.SALARY_EXPECTATION] = _needs(
            AnswerId.SALARY_EXPECTATION,
            "Add your salary expectation, or set it in your preferences.",
        )

    out[AnswerId.WORK_AUTHORIZATION] = (
        _from_profile(
            AnswerId.WORK_AUTHORIZATION,
            "I will need visa sponsorship to work in the country this role is based in.",
        )
        if prefs.needs_sponsorship
        else _needs(
            AnswerId.WORK_AUTHORIZATION,
            "Say whether you are authorized to work in this role's country and whether you need "
            "sponsorship.",
        )
    )

    modes = [_MODES[m] for m in prefs.work_modes]
    if modes and prefs.locations:
        text = f"I am looking for {_join(modes)} roles, based in {_join(prefs.locations)}."
    elif modes:
        text = f"I am looking for {_join(modes)} roles."
    elif prefs.locations:
        text = f"I am interested in roles in {_join(prefs.locations)}."
    else:
        text = ""
    out[AnswerId.RELOCATION_REMOTE] = (
        _from_profile(AnswerId.RELOCATION_REMOTE, text)
        if text
        else _needs(
            AnswerId.RELOCATION_REMOTE,
            "Add your preferred locations and work modes, and whether you would relocate.",
        )
    )

    years = profile.years_experience
    if years is None:
        months = total_experience_months(request.resume, request.as_of or date.today())
        years = months // 12 if months else None
    if years is not None and years > 0 and matching:
        text = (
            f"I have about {years} years of professional experience overall, including work with "
            f"{_join(matching[:5])}."
        )
    elif years is not None and years > 0:
        text = f"I have about {years} years of professional experience overall."
    elif matching:
        text = f"My experience includes work with {_join(matching[:5])}."
    else:
        text = ""
    out[AnswerId.YEARS_KEY_SKILLS] = (
        _from_profile(AnswerId.YEARS_KEY_SKILLS, text)
        if text
        else _needs(
            AnswerId.YEARS_KEY_SKILLS,
            "Add how many years you have used the skills this role asks for.",
        )
    )

    out[AnswerId.HOW_HEARD] = _from_profile(AnswerId.HOW_HEARD, "I found this role on JobFinder.")
    return out


@dataclass(slots=True)
class ScreeningOutcome:
    prompt_version: str
    model: str
    tone: Tone
    length: Length
    answers: list[Answer]
    fact_check: FactCheckResult
    job_text_redactions: int
    notes_redactions: int
    usage: list[LLMUsage] = field(default_factory=list)


async def answer_screening(
    provider: LLMProvider, settings: Settings, request: ScreeningRequest
) -> ScreeningOutcome:
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
    matching, _ = key_skills(request.resume, request.job, description)
    llm_request = LLMRequest(
        user_id=request.user_id,
        feature=FEATURE,
        prompt_version=prompt.prompt_version,
        tier=ModelTier.STRONG,
        system=prompt.text,
        user_message=build_user_message(request, description, notes or None, secrets.token_hex(8)),
        max_tokens=settings.screening_answers_max_tokens,
    )
    generated = await generate_structured(provider, llm_request, LlmScreening)
    written = {a.id: a.answer for a in generated.value.answers}
    facts = factual_answers(request, matching)
    budget = MAX_WORDS[request.length]
    answers: list[Answer] = []
    for answer_id in QUESTIONS:
        if answer_id in written:
            answers.append(
                Answer(
                    id=answer_id,
                    question=QUESTIONS[answer_id],
                    answer=trim_sentences(written[answer_id], budget),
                    status="GENERATED",
                )
            )
        else:
            answers.append(facts[answer_id])
    context = " ".join(x for x in (request.job.title, request.job.company, notes) if x)
    fact = check_texts(
        request.resume,
        [(f"answers[{a.id.value}]", a.answer) for a in answers if a.status == "GENERATED"],
        raw_description or None,
        allowed_context=context,
        years_experience=request.profile.years_experience,
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
    return ScreeningOutcome(
        prompt_version=prompt.prompt_version,
        model=generated.usage[-1].model,
        tone=request.tone,
        length=request.length,
        answers=answers,
        fact_check=fact,
        job_text_redactions=len(removed),
        notes_redactions=len(notes_removed),
        usage=generated.usage,
    )
