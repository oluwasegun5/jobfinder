"""Mock interview (docs/adr/0034-mock-interview.md): one turn of feedback and a session summary.

`generate_turn` makes at most one model call (strong tier): the rubric feedback for the answer and,
when the user's prep has no unasked question left to take, the next question too. The next question
comes from the prep's stored questions in code when there is one. `generate_summary` makes one call
(fast tier) for the narrative and the three next steps; the averages, the top strengths and the top
improvements are computed in code from the stored feedback.

The answer, the job and the feedback texts are untrusted. Instruction-like sentences are removed
from the answer and the posting before a model sees them, the data goes in delimited blocks, and
whatever the model returns is validated and grounded in code (`mock_grounding.py`). Nothing here has
a side effect, and no answer text is logged.
"""

import logging
import secrets
from dataclasses import dataclass, field

from pydantic import BaseModel

from app.config import Settings
from app.factcheck.injection import (
    REDACTION,
    is_instruction_like,
    leaked_instruction_text,
    scrub_job_text,
)
from app.interview.mock_grounding import (
    compute_averages,
    ground_feedback,
    is_repeat,
    pick_prep_question,
    summary_allowed_text,
    template_narrative,
    template_next_steps,
    top_improvements,
    top_strengths,
    unsupported_terms,
)
from app.interview.mock_message import summary_message, turn_message
from app.interview.mock_schema import (
    MAX_ANSWER_CHARS,
    Averages,
    Feedback,
    LlmBehavioralTurn,
    LlmBehavioralTurnWithQuestion,
    LlmNextQuestion,
    LlmOpening,
    LlmOtherTurn,
    LlmOtherTurnWithQuestion,
    LlmSummary,
    MockSummaryRequest,
    MockTurnRequest,
    NextQuestion,
)
from app.interview.schema import Category
from app.llm.base import (
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

TURN_FEATURE = "mock_interview"
SUMMARY_FEATURE = "mock_interview_summary"


def resolve_prompt(name: str, version: str) -> Prompt:
    """`mock_interview/v1` is `mock_turn_v1.md` and `mock_summary_v1.md`."""
    try:
        return load_named_prompt("interview", name, int(version.rsplit("/v", 1)[1]))
    except FileNotFoundError as e:
        raise UnknownPromptVersionError(version) from e


@dataclass(slots=True)
class TurnOutcome:
    prompt_version: str
    model: str | None
    feedback: Feedback | None
    next_question: NextQuestion | None
    dropped_claims: int
    answer_redactions: int
    scores_capped: bool
    usage: list[LLMUsage] = field(default_factory=list)


def _safe_question(question: str, raw_description: str, asked: list[str]) -> bool:
    return not (
        is_repeat(question, asked)
        or is_instruction_like(question)
        or REDACTION in question
        or (bool(raw_description) and leaked_instruction_text(question, raw_description))
    )


def _checked_question(
    llm: LlmNextQuestion, raw_description: str, asked: list[str], usage: list[LLMUsage]
) -> NextQuestion:
    if not _safe_question(llm.question, raw_description, asked):
        failure = LLMOutputValidationError(
            "The generated interview question repeated an earlier one or injected text"
        )
        failure.usage = usage
        raise failure
    return NextQuestion(category=llm.category, question=llm.question, source="generated")


async def generate_turn(
    provider: LLMProvider, settings: Settings, request: MockTurnRequest
) -> TurnOutcome:
    prompt = resolve_prompt("mock_turn", request.prompt_version)
    job = request.job
    raw_description = job.description or ""
    description, _ = (
        scrub_job_text(raw_description, settings.tailor_description_chars)
        if raw_description
        else ("", [])
    )
    skills = [s for s in job.skills if not is_instruction_like(s)]
    asked = list(request.asked)
    given = request.answer

    # The next question comes from the prep when it has one the session has not asked.
    from_prep = None
    if request.need_next and request.prep_questions:
        previous = given.category if given is not None else None
        picked = pick_prep_question(request.prep_questions, asked, previous)
        if picked is not None:
            from_prep = NextQuestion(
                category=picked.category, question=picked.question, source="prep"
            )
    need_generated = request.need_next and from_prep is None

    visible = ""
    redactions: list[str] = []
    if given is not None:
        visible, redactions = scrub_job_text(given.text, MAX_ANSWER_CHARS)
        if redactions:
            logger.warning(
                "Removed %d instruction-like sentence(s) from an answer", len(redactions)
            )

    if given is None and from_prep is not None:
        return TurnOutcome(
            prompt_version=request.prompt_version,
            model=None,
            feedback=None,
            next_question=from_prep,
            dropped_claims=0,
            answer_redactions=0,
            scores_capped=False,
        )

    mode = (
        "question_only"
        if given is None
        else "feedback_and_question"
        if need_generated
        else "feedback_only"
    )
    llm_request = LLMRequest(
        user_id=request.user_id,
        feature=TURN_FEATURE,
        prompt_version=request.prompt_version,
        tier=ModelTier.STRONG,
        system=prompt.text,
        user_message=turn_message(
            request.persona,
            title=job.title,
            company=job.company,
            description=description,
            skills=skills,
            question=given.question if given else None,
            category=given.category.value if given else None,
            answer=visible if given else None,
            asked=asked,
            mode=mode,
            nonce=secrets.token_hex(8),
        ),
        max_tokens=settings.mock_turn_max_tokens,
    )

    schema: type[BaseModel]
    if given is None:
        schema = LlmOpening
    elif given.category is Category.BEHAVIORAL:
        schema = LlmBehavioralTurnWithQuestion if need_generated else LlmBehavioralTurn
    else:
        schema = LlmOtherTurnWithQuestion if need_generated else LlmOtherTurn
    result = await generate_structured(provider, llm_request, schema)
    usage = list(result.usage)
    value = result.value

    feedback: Feedback | None = None
    dropped = 0
    capped = False
    llm_fb = None
    llm_question = None
    if isinstance(
        value,
        LlmBehavioralTurn | LlmOtherTurn | LlmBehavioralTurnWithQuestion | LlmOtherTurnWithQuestion,
    ):
        llm_fb = value.feedback
    if isinstance(value, LlmBehavioralTurnWithQuestion | LlmOtherTurnWithQuestion | LlmOpening):
        llm_question = value.next_question
    if given is not None and llm_fb is not None:
        feedback, dropped, capped = ground_feedback(
            structure=llm_fb.structure,
            relevance=llm_fb.relevance,
            specificity=llm_fb.specificity,
            star=llm_fb.star,
            overall=llm_fb.overall,
            strengths=llm_fb.strengths,
            improvements=llm_fb.improvements,
            answer=visible,
            raw_answer=given.text,
            question=given.question,
            job_text=" ".join([job.title, *skills]),
        )
        if dropped:
            logger.info("Grounding removed %d feedback point(s)", dropped)
    next_question = from_prep
    if need_generated and llm_question is not None:
        next_question = _checked_question(llm_question, raw_description, asked, usage)
    return TurnOutcome(
        prompt_version=request.prompt_version,
        model=usage[-1].model,
        feedback=feedback,
        next_question=next_question,
        dropped_claims=dropped,
        answer_redactions=len(redactions),
        scores_capped=capped,
        usage=usage,
    )


@dataclass(slots=True)
class SummaryOutcome:
    prompt_version: str
    model: str
    turns_answered: int
    averages: Averages
    top_strengths: list[str]
    top_improvements: list[str]
    narrative: str
    next_steps: list[str]
    fallback_used: bool
    usage: list[LLMUsage] = field(default_factory=list)


def _acceptable(text: str, allowed: str) -> bool:
    return not (is_instruction_like(text) or REDACTION in text or unsupported_terms(text, allowed))


async def generate_summary(
    provider: LLMProvider, settings: Settings, request: MockSummaryRequest
) -> SummaryOutcome:
    prompt = resolve_prompt("mock_summary", request.prompt_version)
    turns = request.turns
    averages = compute_averages(turns)
    strengths = top_strengths(turns)
    improvements = top_improvements(turns)
    llm_request = LLMRequest(
        user_id=request.user_id,
        feature=SUMMARY_FEATURE,
        prompt_version=request.prompt_version,
        tier=ModelTier.FAST,
        system=prompt.text,
        user_message=summary_message(
            request.persona,
            title=request.job.title,
            turns=turns,
            averages=averages.model_dump(),
            top_strengths=strengths,
            top_improvements=improvements,
            ended_early=request.ended_early,
            nonce=secrets.token_hex(8),
        ),
        max_tokens=settings.mock_summary_max_tokens,
    )
    result = await generate_structured(provider, llm_request, LlmSummary)
    allowed = summary_allowed_text(turns, averages, request.job.title, request.job.company)

    fallback = False
    narrative = result.value.narrative
    if not _acceptable(narrative, allowed):
        narrative = template_narrative(averages, len(turns), request.ended_early)
        fallback = True
    steps: list[str] = []
    for step in result.value.next_steps:
        if _acceptable(step, allowed) and not is_repeat(step, steps):
            steps.append(step)
        else:
            fallback = True
    for step in template_next_steps(averages):
        if len(steps) == 3:
            break
        if not is_repeat(step, steps):
            steps.append(step)
    if fallback:
        logger.info("Summary used the code's wording for part of the narrative or next steps")
    return SummaryOutcome(
        prompt_version=request.prompt_version,
        model=result.usage[-1].model,
        turns_answered=len(turns),
        averages=averages,
        top_strengths=strengths,
        top_improvements=improvements,
        narrative=narrative,
        next_steps=steps,
        fallback_used=fallback,
        usage=list(result.usage),
    )
