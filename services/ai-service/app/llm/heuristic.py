"""A keyless stand-in for the model, for local runs and evals (`LLM_PROVIDER=fake`).

It answers only the match-scoring prompt, and answers it with a deterministic keyword heuristic over
the same data blocks a real model would read: the share of the job's listed skills the candidate
names, and how much of the job title's wording appears in the candidate's titles. It carries no
understanding, so its scores are a plausible shape for exercising the pipeline (caching, the daily
cap, fallbacks, the eval report), not a judgement of fit. Every record it produces is labelled:
provider `fake`, model `fake-heuristic-v1`, cost 0.
"""

import json
import re
from decimal import Decimal

from app.llm.base import LLMConfigurationError, LLMRequest, LLMResponse, LLMUsage
from app.matching.message import parse_message
from app.matching.schema import Candidate, JobPosting

MODEL = "fake-heuristic-v1"
_FEATURE = "match_scoring"
_WORD = re.compile(r"[a-z0-9+#.]+")
_STOP = frozenset(
    {"and", "the", "for", "with", "of", "to", "in", "at", "a", "an", "or", "sr", "jr"}
)


def _words(text: str) -> set[str]:
    return {w for w in _WORD.findall(text.casefold()) if len(w) > 1 and w not in _STOP}


def _candidate_titles(candidate: Candidate) -> set[str]:
    words: set[str] = set()
    for text in (
        candidate.headline,
        *candidate.target_titles,
        *(r.title for r in candidate.experience),
    ):
        if text:
            words |= _words(text)
    return words


def score_job(candidate: Candidate, job: JobPosting) -> tuple[int, list[str], list[str]]:
    """Score 0-100 plus reasons, from skill coverage (weight 0.7) and title overlap (0.3)."""
    have = {s.casefold(): s for s in candidate.skills}
    wanted = {s.casefold(): s for s in job.skills}
    matched = [wanted[k] for k in wanted if k in have]
    missing = [wanted[k] for k in wanted if k not in have]
    title_words = _words(job.title)
    title_overlap = (
        len(title_words & _candidate_titles(candidate)) / len(title_words) if title_words else 0.0
    )
    coverage = len(matched) / len(wanted) if wanted else 0.0
    fit = 0.7 * coverage + 0.3 * title_overlap if wanted else title_overlap
    score = round(10 + 90 * fit)

    strengths: list[str] = []
    if matched:
        strengths.append(f"You list {', '.join(matched[:5])}, which the job asks for.")
    if title_overlap >= 0.5:
        strengths.append("Your recent titles overlap with this job's title.")
    gaps: list[str] = []
    if missing:
        gaps.append(f"Your profile does not list {', '.join(missing[:5])}, which the job asks for.")
    if job.seniority and candidate.seniority and job.seniority != candidate.seniority:
        gaps.append(f"The job is {job.seniority}; your profile says {candidate.seniority}.")
    return score, strengths, gaps


class HeuristicProvider:
    name = "fake"

    async def generate(self, request: LLMRequest) -> LLMResponse:
        if request.feature != _FEATURE:
            raise LLMConfigurationError(
                f"LLM_PROVIDER=fake only supports {_FEATURE}; set LLM_PROVIDER=anthropic for "
                f"{request.feature}"
            )
        candidate, jobs = parse_message(request.user_message)
        results = []
        for job in jobs:
            score, strengths, gaps = score_job(candidate, job)
            results.append(
                {"job_id": str(job.id), "score": score, "strengths": strengths, "gaps": gaps}
            )
        text = json.dumps({"results": results})
        return LLMResponse(
            text=text,
            usage=LLMUsage(
                user_id=request.user_id,
                feature=request.feature,
                provider=self.name,
                model=MODEL,
                input_tokens=len(request.system.split()) + len(request.user_message.split()),
                output_tokens=len(text.split()),
                cost_usd=Decimal(0),
                latency_ms=0,
                prompt_version=request.prompt_version,
                pricing_version="fake",
            ),
        )

    async def aclose(self) -> None:
        return None
