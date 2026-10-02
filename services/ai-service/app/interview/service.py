"""Interview prep (docs/adr/0033-interview-prep.md): POST /v1/interview-prep.

Two model calls, each schema-validated with one retry (`generate_structured`):

1. Questions (strong model): categorised questions from the posting and the candidate's resume.
2. Company brief (fast model): from the posting and the company record only (the model is not
   shown the resume), every claim naming its source field and quoting it. Code then runs the
   grounding check (`grounding.py`), drops what the fields do not support, and derives the
   `unknowns` the fields leave open. The model's own `unknowns` are kept only if they introduce
   no number or name the fields lack.

The posting, the company fields and the resume are untrusted. Instruction-like sentences are
removed from the posting and from every short field before a model sees it, the data goes in
delimited blocks, output text that repeats an injected sentence is dropped, and nothing here has a
side effect. If the second call fails, the first call's billed usage is still reported.
"""

import logging
import secrets
from dataclasses import dataclass, field

from app.config import Settings
from app.factcheck.injection import is_instruction_like, leaked_instruction_text, scrub_job_text
from app.factcheck.normalize import words
from app.interview.grounding import ground_claims, says_nothing_new
from app.interview.message import brief_message, questions_message
from app.interview.schema import (
    SECTION_TITLES,
    Brief,
    Category,
    Claim,
    InterviewPrepRequest,
    LlmBrief,
    LlmQuestions,
    Question,
    Section,
    SectionId,
    SourceField,
)
from app.llm.base import (
    LLMError,
    LLMOutputValidationError,
    LLMProvider,
    LLMRequest,
    LLMUsage,
    ModelTier,
)
from app.llm.structured import generate_structured
from app.matching.service import UnknownPromptVersionError
from app.prompts import Prompt, load_named_prompt

logger = logging.getLogger(__name__)

QUESTIONS_FEATURE = "interview_questions"
BRIEF_FEATURE = "interview_brief"
MIN_QUESTIONS = 6
MAX_UNKNOWNS = 12

# What the code always says it does not know, whatever the model wrote: these are not in the
# posting or the company record, so the brief cannot speak to them.
ALWAYS_UNKNOWN = (
    "The company's culture, funding, leadership and recent news are not held by JobFinder, so "
    "this brief makes no statement about them."
)
MISSING_FIELD_UNKNOWNS: tuple[tuple[SourceField, str], ...] = (
    (SourceField.COMPANY_SIZE, "The company's size is not in the company record."),
    (SourceField.COMPANY_INDUSTRY, "The company's industry is not in the company record."),
    (SourceField.COMPANY_DOMAIN, "The company's website is not in the company record."),
    (SourceField.JOB_SALARY, "The posting does not state the salary."),
    (SourceField.JOB_LOCATION, "The posting does not say where the role is based."),
    (SourceField.JOB_DESCRIPTION, "The job has no description text."),
)


def resolve_prompts(version: str) -> tuple[Prompt, Prompt]:
    """`interview/v1` is `questions_v1.md` and `brief_v1.md` together."""
    try:
        n = int(version.rsplit("/v", 1)[1])
        return (
            load_named_prompt("interview", "questions", n),
            load_named_prompt("interview", "brief", n),
        )
    except FileNotFoundError as e:
        raise UnknownPromptVersionError(version) from e


@dataclass(slots=True)
class PrepOutcome:
    prompt_version: str
    questions_model: str
    brief_model: str
    questions: list[Question]
    brief: Brief
    job_text_redactions: int
    company_redactions: int
    questions_dropped: int
    usage: list[LLMUsage] = field(default_factory=list)


def _field(value: str | None, redactions: list[str]) -> str | None:
    """A short field made safe to show a model: instruction-like text is dropped and counted."""
    if not value:
        return None
    if is_instruction_like(value):
        redactions.append(value)
        return None
    return value


def build_fields(
    request: InterviewPrepRequest, description: str, redactions: list[str]
) -> tuple[dict[SourceField, str], list[str]]:
    """The labelled texts of the posting and the company record that the brief may cite, and the
    job's skills as a list."""
    job, company = request.job, request.company
    skills = [s for s in (_field(x, redactions) for x in job.skills) if s]
    candidates: list[tuple[SourceField, str | None]] = [
        (SourceField.JOB_TITLE, _field(job.title, redactions)),
        (SourceField.JOB_DESCRIPTION, description or None),
        (SourceField.JOB_LOCATION, _field(job.location, redactions)),
        (SourceField.JOB_WORK_MODE, _field(job.work_mode, redactions)),
        (SourceField.JOB_EMPLOYMENT_TYPE, _field(job.employment_type, redactions)),
        (SourceField.JOB_SENIORITY, _field(job.seniority, redactions)),
        (SourceField.JOB_SALARY, _field(job.salary, redactions)),
        (SourceField.JOB_SKILLS, ", ".join(skills) or None),
        (SourceField.COMPANY_NAME, _field(company.name, redactions)),
        (SourceField.COMPANY_DOMAIN, _field(company.domain, redactions)),
        (SourceField.COMPANY_SIZE, _field(company.size, redactions)),
        (SourceField.COMPANY_INDUSTRY, _field(company.industry, redactions)),
    ]
    return {key: value for key, value in candidates if value}, skills


def finish_questions(
    generated: LlmQuestions, raw_description: str, count: int
) -> tuple[list[Question], int]:
    """Removes repeats and text copied from an injected sentence; keeps every category in the cut.

    Returns the questions (at most `count`, in the model's order) and how many were removed.
    """
    kept: list[Question] = []
    seen: set[tuple[str, ...]] = set()
    for q in generated.questions:
        key = tuple(words(q.question))
        text = f"{q.question} {q.rationale}"
        if key in seen or (raw_description and leaked_instruction_text(text, raw_description)):
            continue
        seen.add(key)
        kept.append(
            Question(
                category=q.category,
                question=q.question,
                rationale=q.rationale,
                difficulty=q.difficulty,
            )
        )
    categories = {q.category for q in kept}
    if len(kept) < MIN_QUESTIONS or categories != set(Category):
        raise LLMOutputValidationError(
            "Interview questions were unusable after removing repeated and injected ones"
        )
    # The first question of each category always makes the cut; the rest fill in order.
    chosen: set[int] = set()
    for category in Category:
        chosen.add(next(i for i, q in enumerate(kept) if q.category is category))
    for i in range(len(kept)):
        if len(chosen) >= count:
            break
        chosen.add(i)
    final = [q for i, q in enumerate(kept) if i in chosen]
    return final, len(generated.questions) - len(kept)


def finish_brief(
    generated: LlmBrief,
    fields: dict[SourceField, str],
    *,
    raw_description: str,
    context: str,
) -> Brief:
    grounded = ground_claims(
        generated.sections, fields, raw_description=raw_description, context=context
    )
    by_section: dict[SectionId, list[Claim]] = {}
    for section_id, claim in grounded.kept:
        by_section.setdefault(section_id, []).append(
            Claim(statement=claim.statement, source=claim.source, evidence=claim.evidence)
        )
    sections = [
        Section(id=section_id, title=SECTION_TITLES[section_id], claims=by_section[section_id])
        for section_id in SectionId
        if section_id in by_section
    ]

    unknowns = [message for key, message in MISSING_FIELD_UNKNOWNS if key not in fields]
    unknowns.append(ALWAYS_UNKNOWN)
    seen = {tuple(words(u)) for u in unknowns}
    for text in generated.unknowns:
        key = tuple(words(text))
        unsafe = is_instruction_like(text) or (
            bool(raw_description) and leaked_instruction_text(text, raw_description)
        )
        if key in seen or unsafe or not says_nothing_new(text, fields, context):
            continue
        seen.add(key)
        unknowns.append(text)
    if grounded.dropped:
        unknowns.append(
            f"{len(grounded.dropped)} statement(s) were removed because the job posting and "
            "company record do not support them."
        )
    return Brief(sections=sections, unknowns=unknowns[:MAX_UNKNOWNS], dropped=grounded.dropped)


async def generate_prep(
    provider: LLMProvider, settings: Settings, request: InterviewPrepRequest
) -> PrepOutcome:
    questions_prompt, brief_prompt = resolve_prompts(request.prompt_version)
    raw_description = request.job.description or ""
    description, removed = (
        scrub_job_text(raw_description, settings.tailor_description_chars)
        if raw_description
        else ("", [])
    )
    company_removed: list[str] = []
    fields, skills = build_fields(request, description, company_removed)
    if removed or company_removed:
        # Counts only: the text is third-party data and never logged.
        logger.warning(
            "Removed %d instruction-like sentence(s) from a job description and %d from fields",
            len(removed),
            len(company_removed),
        )
    nonce = secrets.token_hex(8)
    job = request.job
    company_name = fields.get(SourceField.COMPANY_NAME)
    extra: dict[str, object] = {
        key: value
        for key, value in (
            ("location", fields.get(SourceField.JOB_LOCATION)),
            ("work_mode", fields.get(SourceField.JOB_WORK_MODE)),
            ("employment_type", fields.get(SourceField.JOB_EMPLOYMENT_TYPE)),
            ("seniority", fields.get(SourceField.JOB_SENIORITY)),
            ("skills", skills),
        )
        if value
    }

    questions_request = LLMRequest(
        user_id=request.user_id,
        feature=QUESTIONS_FEATURE,
        prompt_version=questions_prompt.prompt_version,
        tier=ModelTier.STRONG,
        system=questions_prompt.text,
        user_message=questions_message(
            request.resume,
            title=job.title,
            company=company_name,
            description=description,
            extra=extra,
            count=request.question_count,
            nonce=nonce,
        ),
        max_tokens=settings.interview_questions_max_tokens,
    )
    asked = await generate_structured(provider, questions_request, LlmQuestions)
    usage = list(asked.usage)
    try:
        questions, dropped = finish_questions(asked.value, raw_description, request.question_count)
    except LLMError as e:
        e.usage = usage
        raise

    brief_request = LLMRequest(
        user_id=request.user_id,
        feature=BRIEF_FEATURE,
        prompt_version=brief_prompt.prompt_version,
        tier=ModelTier.FAST,
        system=brief_prompt.text,
        user_message=brief_message(fields, secrets.token_hex(8)),
        max_tokens=settings.interview_brief_max_tokens,
    )
    try:
        briefed = await generate_structured(provider, brief_request, LlmBrief)
    except LLMError as e:
        # The questions call was billed even though the whole request failed.
        e.usage = [*usage, *e.usage]
        raise
    usage.extend(briefed.usage)
    brief = finish_brief(
        briefed.value,
        fields,
        raw_description=raw_description,
        context=" ".join(x for x in (company_name, fields.get(SourceField.JOB_TITLE)) if x),
    )
    if brief.dropped:
        logger.info(
            "Grounding removed %d of the brief's claims (%s)",
            len(brief.dropped),
            ", ".join(sorted({d.reason.value for d in brief.dropped})),
        )
    return PrepOutcome(
        prompt_version=questions_prompt.prompt_version,
        questions_model=asked.usage[-1].model,
        brief_model=briefed.usage[-1].model,
        questions=questions,
        brief=brief,
        job_text_redactions=len(removed),
        company_redactions=len(company_removed),
        questions_dropped=dropped,
        usage=usage,
    )
