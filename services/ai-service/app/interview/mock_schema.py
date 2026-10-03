"""Shapes of the mock interview endpoints (docs/adr/0034-mock-interview.md).

`POST /v1/mock-interview/turn` takes the candidate's answer to one question and returns rubric
feedback for it and, when asked, the next question. `POST /v1/mock-interview/summary` takes the
stored feedback of a finished session and returns averages (computed in code) with a narrative.

The candidate's answer, the job text and the feedback texts are untrusted: they are length-limited
here, instruction-like sentences are removed from the answer before a model sees it, and the
model's output is validated strictly (`Llm*` shapes, ranges and star consistency) and then grounded
in code (`mock_grounding.py`): a claim about the answer must quote it.
"""

import re
from enum import StrEnum
from typing import Annotated, Self
from uuid import UUID

from pydantic import (
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    model_validator,
)

from app.api.schemas import UsageRecord
from app.interview.schema import Category
from app.tailoring.schema import Line, OptLine, Posting
from app.writing.common import PlainText

PROMPT_VERSION_PATTERN = r"^mock_interview/v[1-9][0-9]{0,2}$"
MAX_ANSWER_CHARS = 8000

# A rubric score: a whole number from 1 (weak) to 5 (strong). Strict, so 3.5, "3" and true are
# rejected rather than coerced.
Score = Annotated[StrictInt, Field(ge=1, le=5)]


def _spaces(value: object) -> object:
    return re.sub(r"\s+", " ", value).strip() if isinstance(value, str) else value


# A quote of the candidate's answer: any characters (an answer may contain code or markdown, which
# `PlainText` would refuse), whitespace folded to single spaces.
Quote = Annotated[str, BeforeValidator(_spaces), Field(min_length=3, max_length=300)]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Function(StrEnum):
    ENGINEERING = "engineering"
    DATA = "data"
    PRODUCT = "product"
    DESIGN = "design"
    MARKETING = "marketing"
    SALES = "sales"
    OPERATIONS = "operations"
    SUPPORT = "support"
    OTHER = "other"


class Seniority(StrEnum):
    JUNIOR = "junior"
    MID = "mid"
    SENIOR = "senior"
    LEAD = "lead"


class Tone(StrEnum):
    WARM = "warm"
    NEUTRAL = "neutral"
    DIRECT = "direct"


class QuestionStyle(StrEnum):
    CONVERSATIONAL = "conversational"
    BEHAVIORAL_PROBING = "behavioral_probing"
    TECHNICAL_DEPTH = "technical_depth"
    SCENARIO_BASED = "scenario_based"


class Persona(_Strict):
    """The interviewer, chosen by core-api from the job once at session start. Closed vocabularies
    only: a persona changes tone and the style of generated questions, never the rubric."""

    function: Function
    seniority: Seniority
    tone: Tone
    question_style: QuestionStyle


# --- request: turn ---


class MockJob(_Strict):
    title: Line
    company: OptLine = None
    seniority: OptLine = None
    description: Posting = None
    skills: list[Line] = Field(default_factory=list, max_length=60)


class PrepQuestion(_Strict):
    category: Category
    question: Annotated[str, Field(min_length=10, max_length=300)]


class Answer(_Strict):
    """The question being answered, and the candidate's answer (untrusted text)."""

    question: Annotated[str, Field(min_length=10, max_length=300)]
    category: Category
    text: Annotated[str, Field(min_length=1, max_length=MAX_ANSWER_CHARS)]


class MockTurnRequest(_Strict):
    user_id: UUID
    prompt_version: str = Field(default="mock_interview/v1", pattern=PROMPT_VERSION_PATTERN)
    persona: Persona
    job: MockJob
    # `None` asks only for the opening question (a session started without a prep).
    answer: Answer | None = None
    # Whether another question follows this answer (false on the last turn of the session).
    need_next: bool = True
    # Every question asked so far in the session, the current one included. Never repeated.
    asked: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(
        default_factory=list, max_length=30
    )
    # The not yet asked questions of the user's prep for this job, if the session was started
    # from one: the next question is taken from here before one is generated.
    prep_questions: list[PrepQuestion] = Field(default_factory=list, max_length=30)

    @model_validator(mode="after")
    def _a_question_or_an_answer_is_wanted(self) -> Self:
        if self.answer is None and not self.need_next:
            raise ValueError("a request without an answer must ask for a question")
        return self


# --- what the model must return: turn ---


class LlmStar(_Strict):
    """Which parts of Situation, Task, Action, Result the answer has. All null (including the
    score) for a question that is not behavioural; otherwise a score and four booleans."""

    score: Score | None
    situation: StrictBool | None
    task: StrictBool | None
    action: StrictBool | None
    result: StrictBool | None

    @model_validator(mode="after")
    def _consistent(self) -> Self:
        flags = (self.situation, self.task, self.action, self.result)
        if all(f is None for f in flags) and self.score is None:
            return self
        if any(f is None for f in flags) or self.score is None:
            raise ValueError("star needs a score and all four flags, or none of them")
        # An answer that lacks a component cannot score above the number of components it has
        # plus one: 5 needs all four, 1 is for an answer with none.
        if self.score > 1 + sum(bool(f) for f in flags):
            raise ValueError("star score is higher than the components present allow")
        return self


class LlmStrength(_Strict):
    """A strength is a claim about what the candidate said, so it must quote the answer."""

    text: Annotated[PlainText, Field(min_length=5, max_length=300)]
    quote: Quote


class LlmImprovement(_Strict):
    """Advice. `quote` is the part of the answer the advice is about, if it is about one."""

    text: Annotated[PlainText, Field(min_length=5, max_length=300)]
    quote: Quote | None = None


class _LlmFeedbackBase(_Strict):
    structure: Score
    relevance: Score
    specificity: Score
    star: LlmStar
    overall: Score
    strengths: list[LlmStrength] = Field(max_length=5)
    improvements: list[LlmImprovement] = Field(min_length=1, max_length=5)


class LlmBehavioralFeedback(_LlmFeedbackBase):
    @model_validator(mode="after")
    def _star_is_scored(self) -> Self:
        if self.star.score is None:
            raise ValueError("a behavioural question needs the star assessment")
        return self


class LlmOtherFeedback(_LlmFeedbackBase):
    @model_validator(mode="after")
    def _star_is_not_applicable(self) -> Self:
        if self.star.score is not None:
            raise ValueError("star must be null for a question that is not behavioural")
        return self


class LlmNextQuestion(_Strict):
    category: Category
    question: Annotated[PlainText, Field(min_length=10, max_length=300)]


class LlmBehavioralTurn(_Strict):
    feedback: LlmBehavioralFeedback


class LlmOtherTurn(_Strict):
    feedback: LlmOtherFeedback


class LlmBehavioralTurnWithQuestion(_Strict):
    feedback: LlmBehavioralFeedback
    next_question: LlmNextQuestion


class LlmOtherTurnWithQuestion(_Strict):
    feedback: LlmOtherFeedback
    next_question: LlmNextQuestion


class LlmOpening(_Strict):
    next_question: LlmNextQuestion


# --- response: turn ---


class Star(LlmStar):
    pass


class Point(_Strict):
    """A strength or an improvement. `quote` is verbatim from the answer (case and spacing
    ignored); an improvement may have none."""

    text: str
    quote: str | None = None


class Feedback(_Strict):
    structure: Score
    relevance: Score
    specificity: Score
    star: Star
    overall: Score
    strengths: list[Point]
    improvements: list[Point]
    # The quotes behind the strengths and improvements above, without repeats.
    evidence: list[str]


class NextQuestion(_Strict):
    category: Category
    question: str
    # `prep` when it came from the user's stored prep, `generated` when a model wrote it.
    source: str


class MockTurnResponse(BaseModel):
    prompt_version: str
    model: str | None
    feedback: Feedback | None
    next_question: NextQuestion | None
    # Strengths and improvements the grounding check removed (text is not returned: it may carry
    # injected content), and instruction-like sentences taken out of the answer.
    dropped_claims: int
    answer_redactions: int
    # True when no strength had evidence, so the scores were capped.
    scores_capped: bool
    usage: list[UsageRecord]


# --- summary ---


class SummaryTurn(_Strict):
    question: Annotated[str, Field(min_length=1, max_length=300)]
    category: Category
    feedback: Feedback


class MockSummaryRequest(_Strict):
    user_id: UUID
    prompt_version: str = Field(default="mock_interview/v1", pattern=PROMPT_VERSION_PATTERN)
    persona: Persona
    job: MockJob
    turns: list[SummaryTurn] = Field(min_length=1, max_length=30)
    # The session ended before its last turn.
    ended_early: bool = False


class LlmSummary(_Strict):
    narrative: Annotated[PlainText, Field(min_length=20, max_length=900)]
    next_steps: list[Annotated[PlainText, Field(min_length=10, max_length=220)]] = Field(
        min_length=3, max_length=3
    )


class Averages(_Strict):
    structure: float
    relevance: float
    specificity: float
    # Null when no question of the session was behavioural.
    star_completeness: float | None
    overall: float


class MockSummaryResponse(BaseModel):
    prompt_version: str
    model: str | None
    turns_answered: int
    averages: Averages
    top_strengths: list[str]
    top_improvements: list[str]
    narrative: str
    next_steps: list[str]
    # True when the model's narrative or a next step said something the stored feedback does not
    # support and the code's own wording was used instead.
    fallback_used: bool
    usage: list[UsageRecord]
