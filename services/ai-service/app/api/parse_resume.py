"""POST /v1/parse-resume: a CV file in, schema-validated structured JSON plus usage out."""

from uuid import UUID

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.api.deps import LLMProviderDep
from app.api.schemas import UsageRecord
from app.parsing.errors import ResumeInputError
from app.parsing.extract import MAX_FILE_BYTES
from app.parsing.grounding import ParseWarning
from app.parsing.schema import ParsedResume
from app.parsing.service import parse_resume

router = APIRouter(prefix="/v1", tags=["resumes"])


class ParseResumeResponse(BaseModel):
    structured: ParsedResume
    # Items the CV text does not support (see app.parsing.grounding); for the review UI.
    warnings: list[ParseWarning]
    prompt_version: str
    usage: list[UsageRecord]


async def _read_capped(request: Request) -> bytes:
    declared = request.headers.get("content-length")
    if declared is not None and declared.isdigit() and int(declared) > MAX_FILE_BYTES:
        raise ResumeInputError("file_too_large", "The file is too large.", status_code=413)
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > MAX_FILE_BYTES:
            raise ResumeInputError("file_too_large", "The file is too large.", status_code=413)
    return bytes(body)


@router.post("/parse-resume", response_model=ParseResumeResponse)
async def parse_resume_route(
    request: Request, user_id: UUID, provider: LLMProviderDep
) -> ParseResumeResponse:
    """The request body is the raw PDF/DOCX bytes; the format is sniffed, never trusted."""
    outcome = await parse_resume(provider, user_id, await _read_capped(request))
    return ParseResumeResponse(
        structured=outcome.resume,
        warnings=outcome.warnings,
        prompt_version=outcome.prompt_version,
        usage=[UsageRecord.from_usage(u) for u in outcome.usage],
    )
