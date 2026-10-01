"""Resume tailoring (docs/adr/0029-resume-tailoring.md): one strong-model call, guardrails in code,
then an independent fact check.

The pipeline is: scrub the untrusted job text, call the model once (`generate_structured`: one
retry on invalid JSON, then fail loudly), apply the guardrails that code can enforce (`finalize`),
compute the diff itself, and run the deterministic fact check of the result against the source
resume. The fact check shares nothing with the model call; a draft that fails it is still
returned, with its BLOCKING flags, and is never dropped or repaired silently.
"""

import logging
import secrets
from dataclasses import dataclass, field

from app.config import Settings
from app.factcheck import FactCheckResult, FactFlag, FlagCode, check_resume
from app.factcheck.checker import SEVERITY
from app.factcheck.injection import scrub_job_text
from app.llm import LLMProvider, LLMRequest, LLMUsage, ModelTier, generate_structured
from app.matching.service import UnknownPromptVersionError
from app.parsing.schema import ParsedResume
from app.prompts import Prompt, load_prompt
from app.tailoring.diff import diff, finalize
from app.tailoring.message import build_user_message
from app.tailoring.schema import Change, LlmTailorOutput, TailorRequest

logger = logging.getLogger(__name__)

FEATURE = "tailor_resume"


def resolve_prompt(version: str) -> Prompt:
    """The prompt file for a `tailor_resume/vN` reference (the request schema checked its shape)."""
    try:
        return load_prompt(FEATURE, int(version.rsplit("/v", 1)[1]))
    except FileNotFoundError as e:
        raise UnknownPromptVersionError(version) from e


@dataclass(slots=True)
class TailorOutcome:
    prompt_version: str
    model: str
    resume: ParsedResume
    changes: list[Change]
    fact_check: FactCheckResult
    job_text_redactions: int
    usage: list[LLMUsage] = field(default_factory=list)


def _with_redaction_flag(result: FactCheckResult, removed: list[str]) -> FactCheckResult:
    if not removed:
        return result
    flag = FactFlag(
        code=FlagCode.JOB_DESCRIPTION_INJECTION,
        severity=SEVERITY[FlagCode.JOB_DESCRIPTION_INJECTION],
        path="job.description",
        value=" ".join(removed[0].split())[:200],
        message="The job posting contained instructions to an AI, which were removed and ignored.",
    )
    return result.model_copy(
        update={"flags": [*result.flags, flag], "warnings": result.warnings + 1}
    )


async def tailor_resume(
    provider: LLMProvider, settings: Settings, request: TailorRequest
) -> TailorOutcome:
    prompt = resolve_prompt(request.prompt_version)
    raw_description = request.job.description or ""
    description, removed = scrub_job_text(raw_description, settings.tailor_description_chars)
    if removed:
        logger.warning(
            "Removed %d instruction-like sentence(s) from a job description", len(removed)
        )
    llm_request = LLMRequest(
        user_id=request.user_id,
        feature=FEATURE,
        prompt_version=prompt.prompt_version,
        tier=ModelTier.STRONG,
        system=prompt.text,
        user_message=build_user_message(
            request.resume, request.job, description, request.options, secrets.token_hex(8)
        ),
        max_tokens=settings.tailor_resume_max_tokens,
    )
    generated = await generate_structured(provider, llm_request, LlmTailorOutput)
    finished = finalize(request.resume, generated.value, request.options)
    changes = diff(request.resume, finished, generated.value.notes)
    # The raw job text (not the scrubbed one) is what leakage is measured against.
    fact = check_resume(request.resume, finished.resume, raw_description or None)
    return TailorOutcome(
        prompt_version=prompt.prompt_version,
        model=generated.usage[-1].model,
        resume=finished.resume,
        changes=changes,
        fact_check=_with_redaction_flag(fact, removed),
        job_text_redactions=len(removed),
        usage=generated.usage,
    )
