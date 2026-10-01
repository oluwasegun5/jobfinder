"""Match scoring: the rerank stage of PLAN.md section 7, one validated LLM call per chunk of jobs.

A request of many jobs is split into chunks under the job-count and character budgets. Each chunk is
one call to `generate_structured` (one retry on invalid JSON, then fail). A chunk that still fails
does not fail the request: its jobs come back as `failed` with a stable code, and core-api falls
back to the cheap stage-2 score for them. Only a provider that is not configured fails the whole
request, because every chunk would fail the same way.
"""

import logging
import secrets
from dataclasses import dataclass, field
from uuid import UUID

from app.config import Settings
from app.llm import (
    LLMConfigurationError,
    LLMError,
    LLMOutputValidationError,
    LLMProvider,
    LLMProviderError,
    LLMRefusalError,
    LLMRequest,
    LLMUsage,
    ModelTier,
    generate_structured,
)
from app.matching.message import build_user_message, candidate_payload, job_payload
from app.matching.schema import (
    Candidate,
    JobPosting,
    JobScore,
    LlmBatch,
    LlmItem,
    ScoreMatchesRequest,
)
from app.prompts import Prompt, load_prompt

logger = logging.getLogger(__name__)

FEATURE = "match_scoring"


class UnknownPromptVersionError(Exception):
    def __init__(self, version: str) -> None:
        super().__init__(f"unknown prompt version {version}")
        self.version = version


def resolve_prompt(version: str) -> Prompt:
    """The prompt file for a `match_scoring/vN` reference (the request schema checked its shape)."""
    try:
        return load_prompt(FEATURE, int(version.rsplit("/v", 1)[1]))
    except FileNotFoundError as e:
        raise UnknownPromptVersionError(version) from e


# Room for a score and up to ten short reasons per job, plus the wrapper.
_OUTPUT_TOKENS_PER_JOB = 450
_OUTPUT_TOKENS_BASE = 256


@dataclass(slots=True)
class ScoreOutcome:
    results: list[JobScore] = field(default_factory=list)
    usage: list[LLMUsage] = field(default_factory=list)
    model: str | None = None
    prompt_version: str = ""


def chunk_jobs(
    candidate: Candidate, jobs: list[JobPosting], settings: Settings, prompt: Prompt
) -> list[list[JobPosting]]:
    """Greedy chunks of at most N jobs whose input stays under the character budget.

    A single job larger than the budget (its description is cut first, so this is rare) gets a chunk
    of its own rather than being dropped.
    """
    base = len(candidate_payload(candidate)) + len(prompt.text)
    chunks: list[list[JobPosting]] = []
    current: list[JobPosting] = []
    used = base
    for job in jobs:
        size = len(job_payload(job, settings.score_matches_description_chars)) + 120
        full = len(current) >= settings.score_matches_max_jobs_per_call
        if current and (full or used + size > settings.score_matches_max_input_chars):
            chunks.append(current)
            current, used = [], base
        current.append(job)
        used += size
    if current:
        chunks.append(current)
    return chunks


def _failure_code(error: LLMError) -> str:
    match error:
        case LLMRefusalError():
            return "llm_refused"
        case LLMOutputValidationError():
            return "llm_output_invalid"
        case LLMProviderError():
            return "llm_unavailable"
        case _:
            return "llm_error"


async def _score_chunk(
    provider: LLMProvider,
    settings: Settings,
    request: ScoreMatchesRequest,
    prompt: Prompt,
    chunk: list[JobPosting],
    outcome: ScoreOutcome,
) -> None:
    llm_request = LLMRequest(
        user_id=request.user_id,
        feature=FEATURE,
        prompt_version=prompt.prompt_version,
        tier=ModelTier.FAST,
        system=prompt.text,
        user_message=build_user_message(
            request.candidate,
            chunk,
            secrets.token_hex(8),
            settings.score_matches_description_chars,
        ),
        max_tokens=_OUTPUT_TOKENS_BASE + _OUTPUT_TOKENS_PER_JOB * len(chunk),
    )
    try:
        result = await generate_structured(provider, llm_request, LlmBatch)
    except LLMConfigurationError as e:
        # Nothing can be scored; report what earlier chunks already cost.
        e.usage = [*outcome.usage, *e.usage]
        raise
    except LLMError as e:
        logger.warning("Scoring %d jobs failed (%s)", len(chunk), type(e).__name__)
        outcome.usage.extend(e.usage)
        code = _failure_code(e)
        outcome.results.extend(
            JobScore(job_id=job.id, status="failed", error_code=code) for job in chunk
        )
        return
    outcome.usage.extend(result.usage)
    outcome.model = result.usage[-1].model
    scored: dict[UUID, LlmItem] = {}
    for answer in result.value.results:
        # The first answer for an id wins; ids that were not asked about are ignored.
        scored.setdefault(answer.job_id, answer)
    for job in chunk:
        item = scored.get(job.id)
        if item is None:
            outcome.results.append(
                JobScore(job_id=job.id, status="failed", error_code="llm_output_incomplete")
            )
        else:
            outcome.results.append(
                JobScore(
                    job_id=job.id,
                    status="scored",
                    score=item.score,
                    strengths=list(item.strengths),
                    gaps=list(item.gaps),
                )
            )


async def score_matches(
    provider: LLMProvider, settings: Settings, request: ScoreMatchesRequest
) -> ScoreOutcome:
    jobs: dict[UUID, JobPosting] = {}
    for job in request.jobs:
        jobs.setdefault(job.id, job)  # a repeated id is scored once
    prompt = resolve_prompt(request.prompt_version)
    outcome = ScoreOutcome(prompt_version=prompt.prompt_version)
    for chunk in chunk_jobs(request.candidate, list(jobs.values()), settings, prompt):
        await _score_chunk(provider, settings, request, prompt, chunk, outcome)
    return outcome
