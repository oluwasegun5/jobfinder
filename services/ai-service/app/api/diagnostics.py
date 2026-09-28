"""LLM round-trip check: proves provider config, prompt loading, validation and usage reporting."""

from typing import Literal
from uuid import UUID

from fastapi import APIRouter
from pydantic import BaseModel

from app.api.deps import LLMProviderDep
from app.api.schemas import UsageRecord
from app.llm import LLMRequest, ModelTier, generate_structured
from app.prompts import load_prompt

router = APIRouter(prefix="/v1/diagnostics", tags=["diagnostics"])

FEATURE = "diagnostics"
PROMPT = load_prompt(FEATURE, 1)


class LlmCheckRequest(BaseModel):
    user_id: UUID


class LlmCheckOutput(BaseModel):
    status: Literal["ok"]


class LlmCheckResponse(BaseModel):
    status: Literal["ok"]
    usage: list[UsageRecord]


@router.post("/llm", response_model=LlmCheckResponse)
async def llm_check(body: LlmCheckRequest, provider: LLMProviderDep) -> LlmCheckResponse:
    result = await generate_structured(
        provider,
        LLMRequest(
            user_id=body.user_id,
            feature=FEATURE,
            prompt_version=PROMPT.prompt_version,
            tier=ModelTier.FAST,
            system=PROMPT.text,
            user_message="Run the connectivity check.",
            max_tokens=64,
        ),
        LlmCheckOutput,
    )
    return LlmCheckResponse(
        status=result.value.status,
        usage=[UsageRecord.from_usage(u) for u in result.usage],
    )
