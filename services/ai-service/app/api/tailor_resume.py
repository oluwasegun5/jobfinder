"""POST /v1/tailor-resume and POST /v1/fact-check (docs/adr/0029-resume-tailoring.md).

`/tailor-resume` makes one strong-model call and returns the tailored resume, the changes and the
fact check of the result. `/fact-check` is the fact check alone: deterministic, no model, no
usage; core-api calls it again after the user edits a draft and before it approves one.
"""

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field

from app.api.deps import LLMProviderDep, SettingsDep
from app.api.schemas import UsageRecord
from app.factcheck import FactCheckResult, check_resume
from app.parsing.schema import ParsedResume
from app.tailoring.schema import Change, TailorRequest
from app.tailoring.service import tailor_resume

router = APIRouter(prefix="/v1", tags=["tailoring"])


class TailorResumeResponse(BaseModel):
    prompt_version: str
    model: str
    resume: ParsedResume
    changes: list[Change]
    fact_check: FactCheckResult
    # Instruction-like sentences removed from the job text before the model saw it.
    job_text_redactions: int
    # One entry per LLM call made, failed attempts included: every call is billed.
    usage: list[UsageRecord]


@router.post("/tailor-resume", response_model=TailorResumeResponse)
async def tailor_resume_route(
    body: TailorRequest, provider: LLMProviderDep, settings: SettingsDep
) -> TailorResumeResponse:
    outcome = await tailor_resume(provider, settings, body)
    return TailorResumeResponse(
        prompt_version=outcome.prompt_version,
        model=outcome.model,
        resume=outcome.resume,
        changes=outcome.changes,
        fact_check=outcome.fact_check,
        job_text_redactions=outcome.job_text_redactions,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )


class FactCheckRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    # The resume the user uploaded, and the one to check against it.
    source: ParsedResume
    candidate: ParsedResume
    # Lets the check recognise instruction text copied from the posting; optional.
    job_description: str | None = Field(default=None, max_length=60000)


@router.post("/fact-check", response_model=FactCheckResult)
async def fact_check_route(body: FactCheckRequest) -> FactCheckResult:
    return check_resume(body.source, body.candidate, body.job_description)
