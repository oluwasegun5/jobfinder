"""Reports how the matching engine's scores are distributed over the synthetic fixtures.

    uv run python -m evals.match_eval                # keyless: heuristic stage 3, hashed stage 2
    uv run python -m evals.match_eval --json             # the same numbers as JSON
    ANTHROPIC_API_KEY=... uv run python -m evals.match_eval --provider anthropic   # real stage 3
    VOYAGE_API_KEY=... uv run python -m evals.match_eval --embedding voyage        # real stage 2

It runs the three stages of docs/adr/0026-matching-engine.md over `evals/fixtures/match` (three
invented candidates, 48 invented jobs; no real personal data):

* stage 2 is a Python mirror of core-api's `Stage2Scorer`: 0.6 x cosine + 0.3 x skill overlap +
  0.1 x recency, each in 0-1, unknown components dropped and the rest rescaled, on a 0-100 scale.
  `tests/test_match_eval.py` checks it against the same golden cases as the Java tests. The default
  embedding is a hashed bag of words (deterministic, meaningful for shared wording, not semantic);
  `--embedding voyage` uses the real model through the service's own provider.
* stage 3 is the service's own `score_matches` (the prompt, batching, validation and retry of
  production) over the top 30 jobs by stage 2, with the provider you pick. The default is the
  keyless heuristic provider, which is a keyword rule, not a judgement: its numbers show the
  report's shape, and only `--provider anthropic` says anything about the prompt and the model.

The report has, per stage, a histogram and percentiles of the scores, then the Spearman rank
correlation between stage 2 and stage 3 over the jobs that stage 3 scored, and the share of
jobs left unranked (stage 3 failed and the stage-2 score stands in). Not part of CI: the fake
run is deterministic and its output is kept in docs/adr/0026-matching-engine.md.
"""

import argparse
import asyncio
import hashlib
import json
import math
import re
import sys
import uuid
from collections.abc import Collection, Sequence
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from statistics import mean
from typing import Any

from pydantic import SecretStr

from app.config import EmbeddingProviderName, ProviderName, Settings
from app.embeddings import EmbeddingInputType, build_embedding_provider
from app.llm import LLMProvider, build_provider
from app.matching.schema import Candidate, JobPosting, ScoreMatchesRequest
from app.matching.service import score_matches

FIXTURES = Path(__file__).parent / "fixtures" / "match"
RERANK_TOP = 30
JOBS_PER_REQUEST = 10  # core-api's app.matching.llm.batch-size
BUCKETS = 10
_WORD = re.compile(r"[a-z0-9+#.]+")
_DIMENSION = 512


@dataclass(frozen=True)
class Weights:
    vector: float = 0.6
    skills: float = 0.3
    recency: float = 0.1
    half_life_days: float = 21.0


def normalize_skill(skill: str) -> str:
    return " ".join(skill.split()).casefold()


def overlap(resume_skills: Collection[str], job_skills: Collection[str]) -> float | None:
    """Share of the job's distinct skills the resume lists; None when either side is empty."""
    have = {normalize_skill(s) for s in resume_skills if normalize_skill(s)}
    wanted = {normalize_skill(s) for s in job_skills if normalize_skill(s)}
    if not have or not wanted:
        return None
    return len(wanted & have) / len(wanted)


def recency(age_days: float, weights: Weights) -> float:
    return float(0.5 ** (max(0.0, age_days) / weights.half_life_days))


def stage2(
    cosine: float | None,
    resume_skills: Collection[str],
    job_skills: Collection[str],
    age_days: float,
    weights: Weights = Weights(),  # noqa: B008  (frozen, shared on purpose)
) -> float:
    """Mirror of core-api Stage2Scorer.score: 0-100 weighted mean of the known components."""
    total = weights.recency
    weighted = weights.recency * recency(age_days, weights)
    if cosine is not None:
        total += weights.vector
        weighted += weights.vector * min(1.0, max(0.0, cosine))
    skills = overlap(resume_skills, job_skills)
    if skills is not None:
        total += weights.skills
        weighted += weights.skills * skills
    return 0.0 if total <= 0 else 100.0 * weighted / total


# --- stage-2 embeddings -------------------------------------------------------------------------


def _hashed(text: str) -> list[float]:
    vector = [0.0] * _DIMENSION
    for word in _WORD.findall(text.casefold()):
        digest = hashlib.sha256(word.encode()).digest()
        vector[int.from_bytes(digest[:4], "big") % _DIMENSION] += 1.0
    norm = math.sqrt(sum(v * v for v in vector))
    return [v / norm for v in vector] if norm else vector


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na, nb = math.sqrt(sum(x * x for x in a)), math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def candidate_text(c: dict[str, Any]) -> str:
    roles = " ".join(f"{r['title']} {' '.join(r.get('bullets', []))}" for r in c["experience"])
    parts = [c.get("headline"), c.get("summary"), " ".join(c["skills"]), roles]
    return " ".join(p for p in parts if p)


def job_text(j: dict[str, Any]) -> str:
    return " ".join(p for p in (j["title"], " ".join(j["skills"]), j.get("description")) if p)


async def embed(texts: list[str], mode: str, settings: Settings) -> list[list[float]]:
    if mode == "hashed":
        return [_hashed(t) for t in texts]
    provider = build_embedding_provider(settings)
    try:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), 64):
            batch = await provider.embed(texts[start : start + 64], EmbeddingInputType.DOCUMENT)
            vectors.extend(batch.vectors)
        return vectors
    finally:
        await provider.aclose()


# --- statistics ---------------------------------------------------------------------------------


def percentile(values: Sequence[float], q: float) -> float:
    """Linear-interpolated percentile (q in 0-100) of a non-empty sequence."""
    ordered = sorted(values)
    pos = (len(ordered) - 1) * q / 100
    low, high = math.floor(pos), math.ceil(pos)
    return ordered[low] + (ordered[high] - ordered[low]) * (pos - low)


def histogram(values: Sequence[float]) -> list[int]:
    counts = [0] * BUCKETS
    for v in values:
        counts[min(BUCKETS - 1, int(v // (100 / BUCKETS)))] += 1
    return counts


def _ranks(values: Sequence[float]) -> list[float]:
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        for k in range(i, j + 1):
            ranks[order[k]] = (i + j) / 2 + 1  # ties share their average rank
        i = j + 1
    return ranks


def spearman(a: Sequence[float], b: Sequence[float]) -> float | None:
    """Rank correlation, ties averaged; None when undefined (under 3 points or no spread)."""
    if len(a) != len(b) or len(a) < 3:
        return None
    ra, rb = _ranks(a), _ranks(b)
    ma, mb = mean(ra), mean(rb)
    cov = sum((x - ma) * (y - mb) for x, y in zip(ra, rb, strict=True))
    va, vb = sum((x - ma) ** 2 for x in ra), sum((y - mb) ** 2 for y in rb)
    return None if va == 0 or vb == 0 else cov / math.sqrt(va * vb)


# --- the run ------------------------------------------------------------------------------------


def _candidate_model(c: dict[str, Any]) -> Candidate:
    return Candidate.model_validate({k: v for k, v in c.items() if k != "id"})


def _job_model(j: dict[str, Any]) -> JobPosting:
    return JobPosting.model_validate({k: v for k, v in j.items() if k != "posted_days_ago"})


async def evaluate(
    provider: LLMProvider, settings: Settings, embedding: str, top: int = RERANK_TOP
) -> dict[str, Any]:
    candidates = json.loads((FIXTURES / "candidates.json").read_text())["candidates"]
    jobs = json.loads((FIXTURES / "jobs.json").read_text())["jobs"]
    vectors = await embed(
        [candidate_text(c) for c in candidates] + [job_text(j) for j in jobs], embedding, settings
    )
    cand_vectors, job_vectors = vectors[: len(candidates)], vectors[len(candidates) :]

    stage2_all: list[float] = []
    stage3_all: list[float] = []
    pairs2: list[float] = []
    pairs3: list[float] = []
    per_candidate: list[dict[str, Any]] = []
    unranked = considered = calls = 0
    tokens_in = tokens_out = 0
    cost = Decimal(0)
    model = ""
    for c, cv in zip(candidates, cand_vectors, strict=True):
        blended = [
            (
                stage2(cosine(cv, jv), c["skills"], j["skills"], j["posted_days_ago"]),
                j,
            )
            for j, jv in zip(jobs, job_vectors, strict=True)
        ]
        blended.sort(key=lambda item: (-item[0], item[1]["id"]))
        stage2_all += [s for s, _ in blended]
        window = blended[:top]
        considered += len(window)

        stage3: dict[str, int] = {}
        for start in range(0, len(window), JOBS_PER_REQUEST):
            chunk = window[start : start + JOBS_PER_REQUEST]
            outcome = await score_matches(
                provider,
                settings,
                ScoreMatchesRequest(
                    user_id=uuid.uuid5(uuid.NAMESPACE_URL, f"eval:{c['id']}"),
                    candidate=_candidate_model(c),
                    jobs=[_job_model(j) for _, j in chunk],
                ),
            )
            model = outcome.model or model
            for u in outcome.usage:
                calls += 1
                tokens_in += u.input_tokens
                tokens_out += u.output_tokens
                cost += u.cost_usd
            for r in outcome.results:
                if r.status == "scored" and r.score is not None:
                    stage3[str(r.job_id)] = r.score
        scored = [(s, stage3[j["id"]]) for s, j in window if j["id"] in stage3]
        unranked += len(window) - len(scored)
        pairs2 += [a for a, _ in scored]
        pairs3 += [b for _, b in scored]
        stage3_all += [b for _, b in scored]
        per_candidate.append(
            {
                "candidate": c["id"],
                "considered": len(window),
                "scored": len(scored),
                "spearman": spearman([a for a, _ in scored], [b for _, b in scored]),
            }
        )

    return {
        "candidates": len(candidates),
        "jobs": len(jobs),
        "stage2": _summary(stage2_all),
        "stage3": _summary(stage3_all),
        "spearman_stage2_vs_stage3": spearman(pairs2, pairs3),
        "per_candidate": per_candidate,
        "considered": considered,
        "unranked": unranked,
        "unranked_share": unranked / considered if considered else 0.0,
        "llm": {
            "model": model,
            "calls": calls,
            "input_tokens": tokens_in,
            "output_tokens": tokens_out,
            "cost_usd": str(cost),
        },
    }


def _summary(values: Sequence[float]) -> dict[str, Any]:
    if not values:
        return {"n": 0}
    return {
        "n": len(values),
        "mean": round(mean(values), 2),
        "percentiles": {f"p{q}": round(percentile(values, q), 1) for q in (10, 25, 50, 75, 90)},
        "histogram": histogram(values),
    }


def _bars(summary: dict[str, Any]) -> list[str]:
    counts = summary.get("histogram", [])
    peak = max(counts, default=0) or 1
    width = 100 // BUCKETS
    return [
        f"  {i * width:>3}-{(i + 1) * width - (1 if i < BUCKETS - 1 else 0):<3} "
        f"{n:>4}  {'#' * round(40 * n / peak)}"
        for i, n in enumerate(counts)
    ]


def render(report: dict[str, Any], provider: str, embedding: str) -> str:
    lines = [
        f"match-eval: {report['candidates']} candidates x {report['jobs']} jobs; "
        f"stage 3 = {provider} ({report['llm']['model'] or 'n/a'}), stage 2 vectors = {embedding}",
        "",
    ]
    for key, label in (
        ("stage2", "stage 2 (recall blend), all jobs"),
        ("stage3", "stage 3 (model)"),
    ):
        s = report[key]
        lines.append(f"{label}: n={s['n']}")
        if s["n"]:
            pct = "  ".join(f"{k}={v}" for k, v in s["percentiles"].items())
            lines += [f"  mean={s['mean']}  {pct}", *_bars(s)]
        lines.append("")
    rho = report["spearman_stage2_vs_stage3"]
    lines.append(
        "rank correlation stage 2 vs stage 3 (Spearman, scored jobs): "
        + ("n/a" if rho is None else f"{rho:.3f}")
    )
    for c in report["per_candidate"]:
        r = c["spearman"]
        lines.append(
            f"  {c['candidate']:<18} scored {c['scored']}/{c['considered']}  rho="
            + ("n/a" if r is None else f"{r:.3f}")
        )
    lines.append(
        f"unranked: {report['unranked']}/{report['considered']} = {report['unranked_share']:.1%}"
    )
    llm = report["llm"]
    lines.append(
        f"llm: {llm['calls']} call(s), {llm['input_tokens']} in / "
        f"{llm['output_tokens']} out tokens, cost ${llm['cost_usd']}"
    )
    return "\n".join(lines)


def _settings(provider: str, embedding: str) -> Settings:
    # A throwaway token: the eval never serves HTTP. API keys come from the environment.
    return Settings(
        ai_service_token=SecretStr("e" * 32),
        llm_provider=ProviderName.FAKE if provider == "fake" else ProviderName.ANTHROPIC,
        embedding_provider=EmbeddingProviderName.VOYAGE,
    )


async def _main(args: argparse.Namespace) -> int:
    settings = _settings(args.provider, args.embedding)
    if args.provider == "anthropic" and settings.anthropic_api_key is None:
        print(
            "ANTHROPIC_API_KEY is not set; use --provider fake for a keyless run", file=sys.stderr
        )
        return 2
    if args.embedding == "voyage" and settings.voyage_api_key is None:
        print(
            "VOYAGE_API_KEY is not set; use --embedding hashed for a keyless run", file=sys.stderr
        )
        return 2
    provider = build_provider(settings)
    try:
        report = await evaluate(provider, settings, args.embedding, args.top)
    finally:
        await provider.aclose()
    print(
        json.dumps(report, indent=2) if args.json else render(report, args.provider, args.embedding)
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument("--provider", choices=["fake", "anthropic"], default="fake")
    parser.add_argument("--embedding", choices=["hashed", "voyage"], default="hashed")
    parser.add_argument(
        "--top", type=int, default=RERANK_TOP, help="jobs sent to stage 3 per candidate"
    )
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    return asyncio.run(_main(parser.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
