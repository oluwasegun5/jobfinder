"""POST /v1/score-matches: a candidate and jobs in, a score with reasons per job and usage out."""

from fastapi import APIRouter
from pydantic import BaseModel

from app.api.deps import LLMProviderDep, SettingsDep
from app.api.schemas import UsageRecord
from app.matching.schema import JobScore, ScoreMatchesRequest
from app.matching.service import score_matches

router = APIRouter(prefix="/v1", tags=["matching"])


class ScoreMatchesResponse(BaseModel):
    prompt_version: str
    # The model of the last successful call; null when no job was scored.
    model: str | None
    results: list[JobScore]
    # One entry per LLM call made, failed attempts included: every call is billed.
    usage: list[UsageRecord]


@router.post("/score-matches", response_model=ScoreMatchesResponse)
async def score_matches_route(
    body: ScoreMatchesRequest, provider: LLMProviderDep, settings: SettingsDep
) -> ScoreMatchesResponse:
    outcome = await score_matches(provider, settings, body)
    return ScoreMatchesResponse(
        prompt_version=outcome.prompt_version,
        model=outcome.model,
        results=outcome.results,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )
