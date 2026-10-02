"""POST /v1/interview-prep (docs/adr/0033-interview-prep.md): categorised interview questions and a
grounded company brief for one job, in one call. Nothing is stored here; core-api stores the result
and makes the request idempotent per user, job and prompt version."""

from fastapi import APIRouter

from app.api.deps import LLMProviderDep, SettingsDep
from app.api.schemas import UsageRecord
from app.interview.schema import InterviewPrepRequest, InterviewPrepResponse
from app.interview.service import generate_prep

router = APIRouter(prefix="/v1", tags=["interview"])


@router.post("/interview-prep", response_model=InterviewPrepResponse)
async def interview_prep_route(
    body: InterviewPrepRequest, provider: LLMProviderDep, settings: SettingsDep
) -> InterviewPrepResponse:
    outcome = await generate_prep(provider, settings, body)
    return InterviewPrepResponse(
        prompt_version=outcome.prompt_version,
        questions_model=outcome.questions_model,
        brief_model=outcome.brief_model,
        questions=outcome.questions,
        brief=outcome.brief,
        job_text_redactions=outcome.job_text_redactions,
        company_redactions=outcome.company_redactions,
        questions_dropped=outcome.questions_dropped,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )
