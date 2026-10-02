"""Shapes of POST /v1/interview-prep: request, model output and response (docs/adr/0033).

The posting, the company record and the resume are untrusted: they are cleaned and length-limited
here and scrubbed of instruction-like sentences before a model sees them. The model's output is
untrusted too: `LlmQuestions` and `LlmBrief` are the strict shapes it must have, and neither has a
field in which the model could vouch for itself. A brief claim names the field it came from
(`source`) and quotes it (`evidence`); `grounding.py` checks both in code.
"""

from enum import StrEnum
from typing import Annotated, Self
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.api.schemas import UsageRecord
from app.parsing.schema import ParsedResume
from app.tailoring.schema import Line, OptLine, Posting
from app.writing.common import PlainText

PROMPT_VERSION_PATTERN = r"^interview/v[1-9][0-9]{0,2}$"


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Category(StrEnum):
    BEHAVIORAL = "behavioral"
    TECHNICAL = "technical"
    ROLE_SPECIFIC = "role_specific"


class Difficulty(StrEnum):
    EASY = "easy"
    MEDIUM = "medium"
    HARD = "hard"


class SectionId(StrEnum):
    ROLE_OVERVIEW = "ROLE_OVERVIEW"
    COMPANY_FACTS = "COMPANY_FACTS"
    SKILLS_AND_TOOLS = "SKILLS_AND_TOOLS"
    LOGISTICS_AND_PAY = "LOGISTICS_AND_PAY"


SECTION_TITLES: dict[SectionId, str] = {
    SectionId.ROLE_OVERVIEW: "The role",
    SectionId.COMPANY_FACTS: "The company",
    SectionId.SKILLS_AND_TOOLS: "Skills and tools",
    SectionId.LOGISTICS_AND_PAY: "Location, terms and pay",
}


class SourceField(StrEnum):
    """The fields a brief claim may name as its source: the job posting and the company record."""

    JOB_TITLE = "job.title"
    JOB_DESCRIPTION = "job.description"
    JOB_LOCATION = "job.location"
    JOB_WORK_MODE = "job.work_mode"
    JOB_EMPLOYMENT_TYPE = "job.employment_type"
    JOB_SENIORITY = "job.seniority"
    JOB_SALARY = "job.salary"
    JOB_SKILLS = "job.skills"
    COMPANY_NAME = "company.name"
    COMPANY_DOMAIN = "company.domain"
    COMPANY_SIZE = "company.size"
    COMPANY_INDUSTRY = "company.industry"


# --- request ---


class InterviewJob(_Strict):
    title: Line
    description: Posting = None
    location: OptLine = None
    work_mode: OptLine = None
    employment_type: OptLine = None
    seniority: OptLine = None
    # The salary as one line of text ("NGN 500000 to 800000 per YEAR"), made by core-api from the
    # job's structured salary; it is a field a brief claim can cite.
    salary: OptLine = None
    skills: list[Line] = Field(default_factory=list, max_length=60)


class CompanyData(_Strict):
    """What we hold about the employer (the `companies` row). Every field is optional."""

    name: OptLine = None
    domain: OptLine = None
    size: OptLine = None
    industry: OptLine = None


class InterviewPrepRequest(_Strict):
    user_id: UUID
    prompt_version: str = Field(default="interview/v1", pattern=PROMPT_VERSION_PATTERN)
    resume: ParsedResume
    job: InterviewJob
    company: CompanyData = Field(default_factory=CompanyData)
    question_count: int = Field(default=12, ge=6, le=20)


# --- what the models must return ---


class LlmQuestion(_Strict):
    category: Category
    question: Annotated[PlainText, Field(min_length=10, max_length=300)]
    rationale: Annotated[PlainText, Field(min_length=5, max_length=300)]
    difficulty: Difficulty


class LlmQuestions(_Strict):
    questions: list[LlmQuestion] = Field(min_length=6, max_length=30)

    @model_validator(mode="after")
    def _every_category_is_used(self) -> Self:
        missing = {c for c in Category} - {q.category for q in self.questions}
        if missing:
            raise ValueError(f"no question in the categories {sorted(c.value for c in missing)}")
        return self


class LlmClaim(_Strict):
    statement: Annotated[PlainText, Field(min_length=5, max_length=300)]
    source: SourceField
    evidence: Annotated[PlainText, Field(min_length=2, max_length=300)]


class LlmSection(_Strict):
    id: SectionId
    claims: list[LlmClaim] = Field(max_length=12)


class LlmBrief(_Strict):
    sections: list[LlmSection] = Field(max_length=8)
    unknowns: list[Annotated[PlainText, Field(max_length=200)]] = Field(
        default_factory=list, max_length=20
    )


# --- response ---


class Question(_Strict):
    category: Category
    question: str
    rationale: str
    difficulty: Difficulty


class Claim(_Strict):
    statement: str
    source: SourceField
    evidence: str


class Section(_Strict):
    id: SectionId
    title: str
    claims: list[Claim]


class DropReason(StrEnum):
    EMPTY_SOURCE = "EMPTY_SOURCE"
    EVIDENCE_NOT_FOUND = "EVIDENCE_NOT_FOUND"
    UNSUPPORTED_NUMBER = "UNSUPPORTED_NUMBER"
    UNSUPPORTED_NAME = "UNSUPPORTED_NAME"
    UNSUPPORTED_STATEMENT = "UNSUPPORTED_STATEMENT"
    INSTRUCTION_LEAK = "INSTRUCTION_LEAK"
    DUPLICATE = "DUPLICATE"


class DroppedClaim(_Strict):
    """A claim the grounding check removed. Its text is not returned (it may be injected)."""

    source: SourceField
    reason: DropReason


class Brief(_Strict):
    sections: list[Section]
    # What a candidate would want to know that the posting and the company record do not say.
    unknowns: list[str]
    dropped: list[DroppedClaim]


class InterviewPrepResponse(BaseModel):
    prompt_version: str
    questions_model: str
    brief_model: str
    questions: list[Question]
    brief: Brief
    # Instruction-like sentences removed from the posting, and from the company fields, before a
    # model saw them.
    job_text_redactions: int
    company_redactions: int
    # Questions the code removed (duplicates, or text copied from an injected instruction).
    questions_dropped: int
    usage: list[UsageRecord]
