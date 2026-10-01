"""Consumer contract with core-api: the JSON below is what core-api's tests stub ai-service with.

If this fails, the response shape of POST /v1/parse-resume changed. Update core-api's parser
(AiServiceResumeParser) if needed, then regenerate the file:

    UPDATE_CONTRACTS=1 uv run pytest tests/test_core_api_contract.py
"""

import json
import os
import uuid
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient

from app.llm import FakeProvider
from tests.fixtures.cvs import BACKEND_ENGINEER

CONTRACT = (
    Path(__file__).resolve().parents[2]
    / "core-api/src/test/resources/ai-service/parse-resume-ok.json"
)
USER_ID = uuid.UUID("00000000-0000-4000-8000-000000000001")


def _without_volatile(body: dict[str, Any]) -> dict[str, Any]:
    """Token counts move whenever the prompt text does; the shape is what the contract pins."""
    stable: dict[str, Any] = json.loads(json.dumps(body))
    for usage in stable["usage"]:
        usage["input_tokens"] = 0
        usage["output_tokens"] = 0
        # A fresh random id per call; the shape (a UUID string) is what is pinned.
        uuid.UUID(usage["call_id"])
        usage["call_id"] = "00000000-0000-4000-8000-0000000000c1"
    return stable


def test_parse_resume_response_matches_the_contract_core_api_stubs(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(json.dumps(BACKEND_ENGINEER.expected))

    response = client.post(
        "/v1/parse-resume",
        params={"user_id": str(USER_ID)},
        content=BACKEND_ENGINEER.render(),
        headers={"Content-Type": "application/octet-stream"},
    )

    assert response.status_code == 200
    actual = _without_volatile(response.json())
    if os.environ.get("UPDATE_CONTRACTS") or not CONTRACT.exists():
        CONTRACT.parent.mkdir(parents=True, exist_ok=True)
        CONTRACT.write_text(json.dumps(actual, indent=2) + "\n", encoding="utf-8")
    assert actual == json.loads(CONTRACT.read_text(encoding="utf-8"))


SCORE_CONTRACT = (
    Path(__file__).resolve().parents[2]
    / "core-api/src/test/resources/ai-service/score-matches-ok.json"
)


def test_score_matches_response_matches_the_contract_core_api_stubs(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    """One scored job and one the model skipped: both result shapes core-api's client must read.

    If this fails, the response shape of POST /v1/score-matches changed. Update core-api's
    AiServiceMatchClient if needed, then regenerate the file with UPDATE_CONTRACTS=1.
    """
    scored, skipped = uuid.UUID(int=1), uuid.UUID(int=2)
    fake_provider.queue(
        json.dumps(
            {
                "results": [
                    {
                        "job_id": str(scored),
                        "score": 82,
                        "strengths": ["Five years of Java, which the role requires."],
                        "gaps": ["No Kubernetes listed."],
                    }
                ]
            }
        )
    )
    body = {
        "user_id": str(USER_ID),
        "candidate": {"headline": "Backend engineer", "skills": ["Java"]},
        "jobs": [
            {"id": str(scored), "title": "Backend Engineer", "skills": ["Java", "Kubernetes"]},
            {"id": str(skipped), "title": "Platform Engineer"},
        ],
    }

    response = client.post("/v1/score-matches", json=body)

    assert response.status_code == 200
    actual = _without_volatile(response.json())
    if os.environ.get("UPDATE_CONTRACTS") or not SCORE_CONTRACT.exists():
        SCORE_CONTRACT.parent.mkdir(parents=True, exist_ok=True)
        SCORE_CONTRACT.write_text(json.dumps(actual, indent=2) + "\n", encoding="utf-8")
    assert actual == json.loads(SCORE_CONTRACT.read_text(encoding="utf-8"))


_RESOURCES = Path(__file__).resolve().parents[2] / "core-api/src/test/resources/ai-service"


def _pin(name: str, actual: dict[str, Any]) -> None:
    contract = _RESOURCES / name
    if os.environ.get("UPDATE_CONTRACTS") or not contract.exists():
        contract.parent.mkdir(parents=True, exist_ok=True)
        contract.write_text(json.dumps(actual, indent=2) + "\n", encoding="utf-8")
    assert actual == json.loads(contract.read_text(encoding="utf-8"))


def test_tailor_resume_response_matches_the_contract_core_api_stubs(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    """A faithful tailoring with one rewording and a warning: the shapes core-api's client reads.

    If this fails, the response shape of POST /v1/tailor-resume changed. Update core-api's
    AiServiceTailoringClient if needed, then regenerate with UPDATE_CONTRACTS=1.
    """
    from tests.fixtures import tailoring as fx

    reply = fx.faithful()
    reply["skills"].append("Kubernetes")  # a WARNING flag, so the flag shape is pinned too
    fake_provider.queue(fx.llm_reply(reply))

    response = client.post("/v1/tailor-resume", json=fx.request_body())

    assert response.status_code == 200
    _pin("tailor-resume-ok.json", _without_volatile(response.json()))


def test_fact_check_response_matches_the_contract_core_api_stubs(client: TestClient) -> None:
    """POST /v1/fact-check on a draft with an invented degree and an invented skill."""
    from tests.fixtures import tailoring as fx

    candidate = fx.faithful()
    candidate["education"][0]["degree"] = "PhD"
    candidate["skills"].append("Kubernetes")

    response = client.post("/v1/fact-check", json={"source": fx.SOURCE, "candidate": candidate})

    assert response.status_code == 200
    _pin("fact-check-failed.json", response.json())


def test_cover_letter_response_matches_the_contract_core_api_stubs(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    """A faithful letter with one warning-free fact check and one injected sentence redacted.

    If this fails, the response shape of POST /v1/cover-letter changed. Update core-api's
    AiWritingClient if needed, then regenerate with UPDATE_CONTRACTS=1.
    """
    from tests.fixtures import tailoring as fx
    from tests.fixtures import writing as wx

    fake_provider.queue(wx.letter_reply())

    response = client.post(
        "/v1/cover-letter",
        json=wx.letter_request(job=fx.JOB_WITH_INJECTION, tone="formal", length="standard"),
    )

    assert response.status_code == 200
    _pin("cover-letter-ok.json", _without_volatile(response.json()))


def test_cover_letter_with_an_invented_employer_matches_the_blocking_contract(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    from tests.fixtures import tailoring as fx
    from tests.fixtures import writing as wx

    fake_provider.queue(
        wx.letter_reply(wx.with_paragraph(f"I worked at {fx.FAKE_EMPLOYER} before this role."))
    )

    response = client.post("/v1/cover-letter", json=wx.letter_request())

    assert response.status_code == 200
    _pin("cover-letter-blocked.json", _without_volatile(response.json()))


def test_screening_answers_response_matches_the_contract_core_api_stubs(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    """The ten answers: four written (one with a NEW_SKILL warning), the rest from the profile."""
    from tests.fixtures import writing as wx

    fake_provider.queue(wx.answers_reply())

    response = client.post(
        "/v1/screening-answers",
        json=wx.screening_request(
            tone="warm",
            length="short",
            profile={
                "years_experience": 8,
                "preferences": {
                    "locations": ["Lagos"],
                    "work_modes": ["REMOTE"],
                    "min_salary": 6500000,
                    "currency": "NGN",
                },
            },
        ),
    )

    assert response.status_code == 200
    _pin("screening-answers-ok.json", _without_volatile(response.json()))


def test_fact_check_text_response_matches_the_contract_core_api_stubs(client: TestClient) -> None:
    from tests.fixtures import tailoring as fx

    body = {
        "source": fx.SOURCE,
        "texts": [
            {
                "path": "paragraphs[0]",
                "text": "Sincerely, [Your Name], with 25 years of experience.",
            }
        ],
        "as_of": "2026-10-01",
    }

    response = client.post("/v1/fact-check-text", json=body)

    assert response.status_code == 200
    _pin("fact-check-text-failed.json", response.json())
