"""POST /v1/mock-interview/turn and /v1/mock-interview/summary (docs/adr/0034-mock-interview.md).

The turn endpoint gives rubric feedback for one answer and, when asked, the next question; the
summary endpoint gives the averages of a finished session with a narrative and next steps. Nothing
is stored here: core-api owns sessions and turns, meters every call and enforces the limits.
"""

from fastapi import APIRouter

from app.api.deps import LLMProviderDep, SettingsDep
from app.api.schemas import UsageRecord
from app.interview.mock_schema import (
    MockSummaryRequest,
    MockSummaryResponse,
    MockTurnRequest,
    MockTurnResponse,
)
from app.interview.mock_service import generate_summary, generate_turn

router = APIRouter(prefix="/v1/mock-interview", tags=["interview"])


@router.post("/turn", response_model=MockTurnResponse)
async def mock_turn_route(
    body: MockTurnRequest, provider: LLMProviderDep, settings: SettingsDep
) -> MockTurnResponse:
    outcome = await generate_turn(provider, settings, body)
    return MockTurnResponse(
        prompt_version=outcome.prompt_version,
        model=outcome.model,
        feedback=outcome.feedback,
        next_question=outcome.next_question,
        dropped_claims=outcome.dropped_claims,
        answer_redactions=outcome.answer_redactions,
        scores_capped=outcome.scores_capped,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )


@router.post("/summary", response_model=MockSummaryResponse)
async def mock_summary_route(
    body: MockSummaryRequest, provider: LLMProviderDep, settings: SettingsDep
) -> MockSummaryResponse:
    outcome = await generate_summary(provider, settings, body)
    return MockSummaryResponse(
        prompt_version=outcome.prompt_version,
        model=outcome.model,
        turns_answered=outcome.turns_answered,
        averages=outcome.averages,
        top_strengths=outcome.top_strengths,
        top_improvements=outcome.top_improvements,
        narrative=outcome.narrative,
        next_steps=outcome.next_steps,
        fallback_used=outcome.fallback_used,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )
