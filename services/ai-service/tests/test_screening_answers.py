"""POST /v1/screening-answers: written answers are fact-checked, factual ones come from code."""

import json
from typing import Any

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import ProviderName, Settings
from app.llm import FakeProvider
from app.main import create_app
from app.security import SERVICE_TOKEN_HEADER
from tests.conftest import TEST_TOKEN
from tests.fixtures import tailoring as fx
from tests.fixtures import writing as wx

ANSWERS = "/v1/screening-answers"
ORDER = [
    "WHY_COMPANY_ROLE",
    "STRENGTHS",
    "GROWTH_AREA",
    "BIGGEST_ACHIEVEMENT",
    "NOTICE_PERIOD",
    "SALARY_EXPECTATION",
    "WORK_AUTHORIZATION",
    "RELOCATION_REMOTE",
    "YEARS_KEY_SKILLS",
    "HOW_HEARD",
]
PROFILE = {
    "years_experience": 8,
    "preferences": {
        "locations": ["Lagos", "Remote"],
        "work_modes": ["REMOTE", "HYBRID"],
        "min_salary": 6500000,
        "currency": "ngn",
        "needs_sponsorship": True,
    },
}


def ask(client: TestClient, **extra: Any) -> Any:
    return client.post(ANSWERS, json=wx.screening_request(**extra))


def by_id(body: dict[str, Any]) -> dict[str, Any]:
    return {a["id"]: a for a in body["answers"]}


def codes(body: dict[str, Any], severity: str | None = None) -> set[str]:
    return {
        f["code"]
        for f in body["fact_check"]["flags"]
        if severity is None or f["severity"] == severity
    }


def test_the_whole_catalogue_comes_back_in_order(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(wx.answers_reply())

    response = ask(client, profile=PROFILE)

    assert response.status_code == 200
    body = response.json()
    assert [a["id"] for a in body["answers"]] == ORDER
    assert body["prompt_version"] == "screening_answers/v1"
    assert all(a["question"] for a in body["answers"])
    statuses = {a["id"]: a["status"] for a in body["answers"]}
    assert {statuses[i] for i in ORDER[:4]} == {"GENERATED"}
    assert body["fact_check"]["passed"] is True
    assert [u["feature"] for u in body["usage"]] == ["screening_answers"]


def test_factual_answers_come_from_the_profile_by_code(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(wx.answers_reply())

    answers = by_id(ask(client, profile=PROFILE).json())

    assert answers["SALARY_EXPECTATION"]["status"] == "FROM_PROFILE"
    assert answers["SALARY_EXPECTATION"]["answer"] == (
        "My salary expectation is at least NGN 6,500,000 per year."
    )
    assert answers["WORK_AUTHORIZATION"]["status"] == "FROM_PROFILE"
    assert "sponsorship" in answers["WORK_AUTHORIZATION"]["answer"]
    assert answers["RELOCATION_REMOTE"]["answer"] == (
        "I am looking for remote and hybrid roles, based in Lagos and Remote."
    )
    years = answers["YEARS_KEY_SKILLS"]
    assert years["status"] == "FROM_PROFILE"
    assert years["answer"].startswith("I have about 8 years of professional experience overall")
    assert "Java" in years["answer"]
    assert "Kubernetes" not in years["answer"]  # asked for, but not on the resume
    assert answers["HOW_HEARD"]["status"] == "FROM_PROFILE"


def test_facts_the_profile_lacks_are_needs_input_never_invented(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(wx.answers_reply())

    body = ask(client).json()
    answers = by_id(body)

    for answer_id in (
        "NOTICE_PERIOD",
        "SALARY_EXPECTATION",
        "WORK_AUTHORIZATION",
        "RELOCATION_REMOTE",
    ):
        assert answers[answer_id]["status"] == "NEEDS_INPUT", answer_id
        assert answers[answer_id]["answer"] == ""
        assert answers[answer_id]["hint"]
    # Nothing in the response invents a number for the missing facts.
    text = " ".join(a["answer"] for a in body["answers"])
    assert "salary" not in text.casefold()
    assert "notice" not in text.casefold()
    # Years come from the resume's own dates when the profile has no figure (2017-06 to 2026-10).
    assert answers["YEARS_KEY_SKILLS"]["answer"].startswith("I have about 9 years")


def test_sponsorship_is_only_stated_when_the_user_said_so(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(wx.answers_reply())

    answers = by_id(ask(client, profile={"preferences": {"needs_sponsorship": False}}).json())

    assert answers["WORK_AUTHORIZATION"]["status"] == "NEEDS_INPUT"


def test_the_model_never_sees_the_factual_inputs_and_cannot_write_their_answers(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(wx.answers_reply())

    ask(client, profile=PROFILE)

    sent = fake_provider.requests[0].user_message
    for secret in ("6500000", "6,500,000", "NGN", "sponsorship", "jordan.ikeji@example.test"):
        assert secret not in sent
    assert "<<<CONTEXT_BEGIN " in sent
    assert '"matching_skills"' in sent
    assert '"gaps": ["Kubernetes"]' in sent


def test_a_model_answering_a_factual_question_is_rejected_and_retried(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    data = wx.faithful_answers()
    data["answers"].append(
        {"id": "SALARY_EXPECTATION", "answer": "I expect NGN 90,000,000 a year."}
    )
    fake_provider.queue(json.dumps(data), json.dumps(data))

    response = ask(client, profile=PROFILE)

    assert response.status_code == 502
    assert response.json()["code"] == "llm_output_invalid"
    assert len(response.json()["usage"]) == 2


@pytest.mark.parametrize(
    "bad",
    [
        {"answers": wx.faithful_answers()["answers"][:3]},
        {"answers": [*wx.faithful_answers()["answers"], wx.faithful_answers()["answers"][0]]},
    ],
)
def test_a_missing_or_duplicated_written_answer_is_rejected(
    client: TestClient, fake_provider: FakeProvider, bad: dict[str, Any]
) -> None:
    fake_provider.queue(json.dumps(bad), json.dumps(bad))

    assert ask(client).status_code == 502


@pytest.mark.parametrize(
    "text",
    [
        "[Company] is where I want to work because the role builds on my Java skills.",
        "I want to work here for NEEDS_INPUT reasons and because of my Java skills.",
        "**Java** is my strongest skill and I have used it at Northwind Systems.",
    ],
)
def test_placeholders_and_markdown_in_a_written_answer_are_refused(
    client: TestClient, fake_provider: FakeProvider, text: str
) -> None:
    bad = json.dumps(wx.with_answer("WHY_COMPANY_ROLE", text))
    fake_provider.queue(bad, bad)

    response = ask(client)

    assert response.status_code == 502
    assert response.json()["code"] == "llm_output_invalid"


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("I worked at Zentrix Dynamics as CTO before joining Northwind Systems.", "NEW_EMPLOYER"),
        ("I hold a PhD in distributed systems and apply it every day to Java work.", "NEW_DEGREE"),
        (
            "With 15 years of experience I am ready for this Java role at once.",
            "NEW_EXPERIENCE_YEARS",
        ),
        (
            "I am AWS Certified Solutions Architect and I use Java every single day.",
            "NEW_CERTIFICATION",
        ),
    ],
)
def test_invented_facts_in_a_written_answer_are_blocking(
    client: TestClient, fake_provider: FakeProvider, text: str, expected: str
) -> None:
    fake_provider.queue(wx.answers_reply(wx.with_answer("STRENGTHS", text)))

    body = ask(client).json()

    assert expected in codes(body, "BLOCKING")
    assert body["fact_check"]["passed"] is False
    flag = next(f for f in body["fact_check"]["flags"] if f["code"] == expected)
    assert flag["path"] == "answers[STRENGTHS]"


def test_instructions_in_the_job_text_do_not_reach_the_model_or_the_answers(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(wx.answers_reply())
    job = {**fx.JOB_WITH_INJECTION, "skills": ["Java"]}

    body = ask(client, job=job).json()

    sent = fake_provider.requests[0].user_message
    assert fx.FAKE_EMPLOYER not in sent
    assert "ignore all previous" not in sent.lower()
    assert body["job_text_redactions"] == 1
    assert "JOB_DESCRIPTION_INJECTION" in codes(body, "WARNING")
    assert fx.FAKE_EMPLOYER not in json.dumps(body["answers"])


def test_an_injected_sentence_copied_into_an_answer_is_a_leak(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(
        wx.answers_reply(wx.with_answer("STRENGTHS", f"Java is my strength. {fx.INJECTION}"))
    )

    body = ask(client, job={**fx.JOB_WITH_INJECTION, "skills": ["Java"]}).json()

    assert "INJECTION_LEAKAGE" in codes(body, "BLOCKING")


def test_the_length_option_limits_each_written_answer(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    sentence = "I applied Java and Spring Boot at Northwind Systems every single day."
    fake_provider.queue(wx.answers_reply(wx.with_answer("STRENGTHS", " ".join([sentence] * 12))))

    body = ask(client, length="short").json()

    assert len(by_id(body)["STRENGTHS"]["answer"].split()) <= 50


@pytest.mark.parametrize(
    "bad",
    [
        {"tone": "angry"},
        {"length": "huge"},
        {"profile": {"preferences": {"work_modes": ["ANYWHERE"]}}},
        {"profile": {"preferences": {"currency": "NAIRA"}}},
        {"profile": {"years_experience": 200}},
        {"notes": "x" * 1001},
        {"extra": 1},
    ],
)
def test_bad_input_is_a_422_and_no_model_call(
    client: TestClient, fake_provider: FakeProvider, bad: dict[str, Any]
) -> None:
    response = ask(client, **bad)

    assert response.status_code == 422
    assert fake_provider.requests == []


# --- the keyless provider ---


@pytest.fixture
def fake_mode_client() -> Any:
    settings = Settings(
        ai_service_token=SecretStr(TEST_TOKEN),
        rabbitmq_enabled=False,
        llm_provider=ProviderName.FAKE,
    )
    app = create_app(settings)
    with TestClient(app, headers={SERVICE_TOKEN_HEADER: TEST_TOKEN}) as c:
        yield c


def test_the_keyless_provider_answers_deterministically_and_faithfully(
    fake_mode_client: TestClient,
) -> None:
    first = ask(fake_mode_client, profile=PROFILE).json()
    second = ask(fake_mode_client, profile=PROFILE).json()

    assert first["answers"] == second["answers"]
    assert first["model"] == "fake-heuristic-v1"
    assert first["usage"][0]["cost_usd"] == "0"
    assert first["fact_check"]["blocking"] == 0
    assert first["fact_check"]["passed"] is True
    assert [a["id"] for a in first["answers"]] == ORDER


def test_the_keyless_provider_changes_wording_with_tone_and_size_with_length(
    fake_mode_client: TestClient,
) -> None:
    tones = {
        t: by_id(ask(fake_mode_client, tone=t).json())["WHY_COMPANY_ROLE"]["answer"]
        for t in ("formal", "warm", "concise")
    }
    sizes = {
        n: len(by_id(ask(fake_mode_client, length=n).json())["WHY_COMPANY_ROLE"]["answer"].split())
        for n in ("short", "standard", "long")
    }

    assert len(set(tones.values())) == 3
    assert sizes["short"] < sizes["standard"] < sizes["long"]
