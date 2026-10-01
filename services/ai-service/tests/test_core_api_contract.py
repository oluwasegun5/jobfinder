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
