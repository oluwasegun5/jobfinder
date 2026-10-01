"""Request, response and LLM-output shapes of POST /v1/score-matches (docs/adr/0026-matching-
engine.md).

The candidate and job fields are untrusted text (a CV, a scraped posting): every string is stripped
of control characters and length-bounded, every list is bounded, and unknown fields are rejected.
The LLM output is untrusted too: `LlmBatch` is the strict shape it must have, and it carries only a
score and reasons, never anything that could trigger an action.
"""

import re
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field

_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def _clean(value: object) -> object:
    if isinstance(value, str):
        return _CONTROL_CHARS.sub(" ", value).strip()
    return value


def _clean_or_none(value: object) -> object:
    cleaned = _clean(value)
    return None if cleaned == "" else cleaned


Tag = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=100)]
Line = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=400)]
OptTag = Annotated[Annotated[str, Field(max_length=100)] | None, BeforeValidator(_clean_or_none)]
OptLine = Annotated[Annotated[str, Field(max_length=400)] | None, BeforeValidator(_clean_or_none)]
OptText = Annotated[Annotated[str, Field(max_length=12000)] | None, BeforeValidator(_clean_or_none)]
# Reasons the model writes: at most 5, each a short plain sentence.
Reason = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=400)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class CandidateRole(_Strict):
    title: Tag
    company: OptTag = None
    period: OptTag = None
    bullets: list[Line] = Field(default_factory=list, max_length=6)


class Candidate(_Strict):
    """The compact profile core-api builds from the primary resume (no contact details or links)."""

    headline: OptLine = None
    summary: OptText = None
    seniority: OptTag = None
    years_experience: int | None = Field(default=None, ge=0, le=80)
    target_titles: list[Tag] = Field(default_factory=list, max_length=20)
    skills: list[Tag] = Field(default_factory=list, max_length=100)
    experience: list[CandidateRole] = Field(default_factory=list, max_length=12)
    education: list[Line] = Field(default_factory=list, max_length=8)
    certifications: list[Line] = Field(default_factory=list, max_length=15)


class JobPosting(_Strict):
    id: UUID
    title: Line
    company: OptLine = None
    location: OptLine = None
    work_mode: OptTag = None
    employment_type: OptTag = None
    seniority: OptTag = None
    salary: OptTag = None
    skills: list[Tag] = Field(default_factory=list, max_length=60)
    description: OptText = None


class ScoreMatchesRequest(_Strict):
    user_id: UUID
    # core-api pins the prompt it caches scores under; scoring uses exactly that version.
    prompt_version: str = Field(
        default="match_scoring/v1", pattern=r"^match_scoring/v[1-9][0-9]{0,2}$"
    )
    candidate: Candidate
    jobs: list[JobPosting] = Field(min_length=1, max_length=50)


class JobScore(BaseModel):
    """One job's outcome. `failed` jobs carry a stable `error_code` and no score."""

    model_config = ConfigDict(frozen=True)

    job_id: UUID
    status: Literal["scored", "failed"]
    score: int | None = None
    strengths: list[str] = Field(default_factory=list)
    gaps: list[str] = Field(default_factory=list)
    error_code: str | None = None


class LlmItem(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    job_id: UUID
    score: Annotated[int, Field(ge=0, le=100)]
    strengths: list[Reason] = Field(default_factory=list, max_length=5)
    gaps: list[Reason] = Field(default_factory=list, max_length=5)


class LlmBatch(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    results: list[LlmItem] = Field(max_length=50)
