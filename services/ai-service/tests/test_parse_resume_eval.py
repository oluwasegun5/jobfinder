"""The eval script itself runs against the real provider by hand; these keep its scoring honest."""

import json

import pytest

from app.llm import FakeProvider
from app.parsing.schema import ParsedResume
from evals.parse_resume import _run_fixture, leaked, score
from tests.fixtures.cvs import ALL_FIXTURES, BACKEND_ENGINEER, CvFixture


def _parsed(fixture: CvFixture) -> ParsedResume:
    return ParsedResume.model_validate(fixture.expected)


@pytest.mark.parametrize("fixture", ALL_FIXTURES, ids=lambda f: f.name)
async def test_a_faithful_parse_scores_full_marks(fixture: CvFixture) -> None:
    provider = FakeProvider([json.dumps(fixture.expected)])
    _, scores = await _run_fixture(provider, fixture)
    assert scores == {"contact": 1.0, "experience": 1.0, "education": 1.0, "skills": 1.0}


def test_wrong_and_missing_content_lowers_the_score() -> None:
    expected = _parsed(BACKEND_ENGINEER)
    damaged = expected.model_copy(
        update={
            "skills": expected.skills[:4],
            "experience": expected.experience[:1],
            "education": [],
        }
    )
    scores = score(expected, damaged)
    assert scores["skills"] == pytest.approx(2 * 1.0 * 0.5 / 1.5)
    assert scores["experience"] == pytest.approx(5 / 15)
    assert scores["education"] == 0.0
    assert scores["contact"] == 1.0


def test_leak_detection_flags_hijacked_output() -> None:
    honest = _parsed(ALL_FIXTURES[-1])
    assert leaked(honest) == []
    hijacked = honest.model_copy(update={"skills": [*honest.skills, "Kubernetes"]})
    assert leaked(hijacked) == ["kubernetes"]
