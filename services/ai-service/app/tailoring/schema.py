"""Request, response and LLM-output shapes of POST /v1/tailor-resume (docs/adr/0029-resume-
tailoring.md).

The resume is the parser's `ParsedResume` in and out, so the tailored output has exactly the shape
of the source (PLAN.md section 7). The job text is untrusted: it is cleaned and length-limited
here and again (instruction-like sentences removed) before a model sees it. The model's output is
untrusted too: `LlmTailorOutput` is the strict shape it must have, and it carries no field in which
the model could describe or vouch for itself.
"""

import re
from enum import StrEnum
from typing import Annotated, Any
from uuid import UUID

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field

from app.parsing.schema import ParsedResume

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")


def _clean(value: object) -> object:
    if isinstance(value, str):
        return _CONTROL_CHARS.sub(" ", value).strip()
    return value


def _clean_or_none(value: object) -> object:
    cleaned = _clean(value)
    return None if cleaned == "" else cleaned


Line = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=400)]
OptLine = Annotated[Annotated[str, Field(max_length=400)] | None, BeforeValidator(_clean_or_none)]
# The posting is cut to a configured length later; this only bounds what the endpoint will read.
Posting = Annotated[Annotated[str, Field(max_length=60000)] | None, BeforeValidator(_clean_or_none)]
Rationale = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=300)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class TailorJob(_Strict):
    title: Line
    company: OptLine = None
    description: Posting = None


class TailorOptions(_Strict):
    # False keeps the source summary and headline exactly as they are.
    rewrite_summary: bool = True
    # At most this many bullets per role (the most relevant ones are kept); None keeps them all.
    max_bullets_per_role: int | None = Field(default=None, ge=1, le=25)


class TailorRequest(_Strict):
    user_id: UUID
    prompt_version: str = Field(
        default="tailor_resume/v1", pattern=r"^tailor_resume/v[1-9][0-9]{0,2}$"
    )
    resume: ParsedResume
    job: TailorJob
    options: TailorOptions = Field(default_factory=TailorOptions)


class ChangeNote(BaseModel):
    """Why the model changed something: `path` names the part, `rationale` says why."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    path: Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=80)]
    rationale: Rationale


class LlmTailorOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    resume: ParsedResume
    notes: list[ChangeNote] = Field(default_factory=list, max_length=60)


class Section(StrEnum):
    HEADLINE = "HEADLINE"
    SUMMARY = "SUMMARY"
    EXPERIENCE = "EXPERIENCE"
    EDUCATION = "EDUCATION"
    SKILLS = "SKILLS"
    PROJECTS = "PROJECTS"
    CERTIFICATIONS = "CERTIFICATIONS"


class ChangeOp(StrEnum):
    # The unit exists in the source and the tailored resume, with different content.
    REPLACE = "REPLACE"
    # The tailored resume has a unit the source does not.
    ADD = "ADD"
    # The source has a unit the tailored resume lacks.
    REMOVE = "REMOVE"


class Change(BaseModel):
    """One reviewable unit of difference between the source and the tailored resume.

    A unit is the headline, the summary, the whole skills list, or one entry of experience,
    education, projects or certifications. `path` addresses the unit in the SOURCE for REPLACE and
    REMOVE (`experience[1]`), and in the TAILORED resume for ADD. `before` and `after` are the
    unit's JSON (a string, a list of strings, or an entry object); `before` is null for ADD and
    `after` is null for REMOVE.
    """

    model_config = ConfigDict(frozen=True)

    id: str
    section: Section
    op: ChangeOp
    path: str
    before: Any
    after: Any
    rationale: str
