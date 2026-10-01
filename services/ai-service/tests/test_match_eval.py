"""The match eval's arithmetic, kept honest: its stage-2 mirror agrees with core-api's goldens."""

import json
from pathlib import Path

import pytest
from pydantic import SecretStr

from app.config import EmbeddingProviderName, ProviderName, Settings
from app.llm import HeuristicProvider
from evals import match_eval
from evals.match_eval import (
    Weights,
    cosine,
    evaluate,
    histogram,
    overlap,
    percentile,
    recency,
    spearman,
    stage2,
)

GOLDEN = (
    Path(__file__).resolve().parents[2] / "core-api/src/test/resources/matching/stage2-golden.json"
)


def _settings() -> Settings:
    return Settings(
        ai_service_token=SecretStr("t" * 32),
        llm_provider=ProviderName.FAKE,
        embedding_provider=EmbeddingProviderName.VOYAGE,
    )


def _golden_cases() -> list[dict[str, object]]:
    cases: list[dict[str, object]] = json.loads(GOLDEN.read_text())["cases"]
    return cases


@pytest.mark.parametrize("case", _golden_cases(), ids=lambda c: str(c["name"]))
def test_stage2_agrees_with_the_golden_cases_core_api_is_tested_against(
    case: dict[str, object],
) -> None:
    golden = json.loads(GOLDEN.read_text())
    weights = Weights(
        vector=golden["weights"]["vector"],
        skills=golden["weights"]["skills"],
        recency=golden["weights"]["recency"],
        half_life_days=golden["half_life_days"],
    )
    score = stage2(
        case["cosine"],  # type: ignore[arg-type]
        case["resume_skills"],  # type: ignore[arg-type]
        case["job_skills"],  # type: ignore[arg-type]
        case["age_days"],  # type: ignore[arg-type]
        weights,
    )
    assert score == pytest.approx(case["expected"], abs=1e-9)


def test_overlap_and_recency_edges() -> None:
    assert overlap([], ["a"]) is None
    assert overlap(["a"], [" "]) is None
    assert overlap(["Spring  Boot"], ["spring boot", "kafka"]) == 0.5
    assert recency(0, Weights()) == 1.0
    assert recency(-5, Weights()) == 1.0
    assert recency(42, Weights()) == pytest.approx(0.25)


def test_percentiles_and_histogram_buckets() -> None:
    assert percentile([10, 20, 30, 40], 50) == 25
    assert percentile([5], 90) == 5
    counts = histogram([0, 9.9, 10, 55, 99.9, 100])
    assert counts[0] == 2 and counts[1] == 1 and counts[5] == 1
    assert counts[9] == 2  # 100 falls in the last bucket


def test_spearman_handles_ties_perfect_order_and_undefined_cases() -> None:
    assert spearman([1, 2, 3, 4], [10, 20, 30, 40]) == pytest.approx(1.0)
    assert spearman([1, 2, 3, 4], [40, 30, 20, 10]) == pytest.approx(-1.0)
    assert spearman([1, 2, 2, 4], [1, 2, 2, 4]) == pytest.approx(1.0)
    assert spearman([1, 2], [1, 2]) is None
    assert spearman([5, 5, 5], [1, 2, 3]) is None


def test_cosine_of_identical_and_orthogonal_vectors() -> None:
    assert cosine([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)
    assert cosine([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)
    assert cosine([0.0, 0.0], [1.0, 0.0]) == 0.0


async def test_the_keyless_run_is_deterministic_and_covers_every_window_job() -> None:
    first = await evaluate(HeuristicProvider(), _settings(), "hashed")
    second = await evaluate(HeuristicProvider(), _settings(), "hashed")

    assert first == second
    assert first["candidates"] == 3 and first["jobs"] == 48
    assert first["stage2"]["n"] == 144
    assert first["considered"] == 90 and first["stage3"]["n"] == 90
    assert first["unranked"] == 0 and first["unranked_share"] == 0.0
    assert sum(first["stage3"]["histogram"]) == 90
    assert first["llm"]["model"] == "fake-heuristic-v1" and first["llm"]["cost_usd"] == "0"
    rho = first["spearman_stage2_vs_stage3"]
    assert rho is not None and 0.0 < rho <= 1.0


async def test_jobs_the_model_fails_on_count_as_unranked() -> None:
    class Failing(HeuristicProvider):
        async def generate(self, request):  # type: ignore[no-untyped-def]
            from app.llm.base import LLMProviderError

            raise LLMProviderError("down", retryable=False)

    report = await evaluate(Failing(), _settings(), "hashed", top=5)

    assert report["considered"] == 15
    assert report["unranked"] == 15 and report["unranked_share"] == 1.0
    assert report["stage3"]["n"] == 0


def test_the_report_renders_and_a_real_provider_without_a_key_is_refused(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setattr("sys.argv", ["match_eval", "--provider", "anthropic"])

    assert match_eval.main() == 2
    assert "ANTHROPIC_API_KEY is not set" in capsys.readouterr().err

    monkeypatch.setattr("sys.argv", ["match_eval"])
    assert match_eval.main() == 0
    out = capsys.readouterr().out
    assert "stage 2 (recall blend)" in out and "unranked: 0/90" in out
