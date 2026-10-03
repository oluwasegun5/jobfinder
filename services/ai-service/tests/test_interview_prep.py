"""POST /v1/interview-prep: categorised questions, a grounded brief and the guardrails."""

import json
from typing import Any

from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import ProviderName, Settings
from app.llm import FakeProvider, HeuristicProvider, ModelTier
from app.main import create_app
from app.security import SERVICE_TOKEN_HEADER
from tests.conftest import TEST_TOKEN
from tests.fixtures import interview as ix
from tests.fixtures import tailoring as fx

PATH = "/v1/interview-prep"


def prep(client: TestClient, body: dict[str, Any] | None = None) -> Any:
    return client.post(PATH, json=body or ix.request_body())


def script(provider: FakeProvider, questions: str | None = None, brief: str | None = None) -> None:
    provider.queue(questions or ix.questions_reply(), brief or ix.brief_reply())


def claims(body: dict[str, Any]) -> list[dict[str, str]]:
    return [c for s in body["brief"]["sections"] for c in s["claims"]]


# --- the fixture job ---


def test_a_fixture_job_gets_categorised_questions_and_a_brief_with_sources_and_unknowns(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    script(fake_provider)

    response = prep(client)

    assert response.status_code == 200
    body = response.json()
    assert body["prompt_version"] == "interview/v1"
    assert {q["category"] for q in body["questions"]} == {
        "behavioral",
        "technical",
        "role_specific",
    }
    assert {q["difficulty"] for q in body["questions"]} == {"easy", "medium", "hard"}
    assert all(q["question"] and q["rationale"] for q in body["questions"])
    assert len(body["questions"]) == 9
    # Every claim names the field it came from and quotes it.
    assert [s["id"] for s in body["brief"]["sections"]] == [
        "ROLE_OVERVIEW",
        "COMPANY_FACTS",
        "SKILLS_AND_TOOLS",
        "LOGISTICS_AND_PAY",
    ]
    assert {c["source"] for c in claims(body)} == {
        "job.description",
        "company.industry",
        "company.size",
        "job.skills",
        "job.location",
    }
    assert all(c["evidence"] for c in claims(body))
    assert body["brief"]["dropped"] == []
    # What the posting and the record leave open is listed, not stated: no salary in the fixture.
    unknowns = body["brief"]["unknowns"]
    assert "The posting does not state the salary." in unknowns
    assert "The size of the engineering team is not stated." in unknowns
    assert any("culture, funding, leadership" in u for u in unknowns)
    assert not any("company's size" in u for u in unknowns)  # the record has it


def test_the_question_count_option_cuts_the_list_but_keeps_every_category(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    script(fake_provider)

    body = prep(client, ix.request_body(question_count=6)).json()

    assert len(body["questions"]) == 6
    assert {q["category"] for q in body["questions"]} == {
        "behavioral",
        "technical",
        "role_specific",
    }


def test_two_calls_are_made_questions_on_the_strong_model_and_brief_on_the_fast_one(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    script(fake_provider)

    body = prep(client).json()

    questions_call, brief_call = fake_provider.requests
    assert (questions_call.feature, questions_call.tier) == (
        "interview_questions",
        ModelTier.STRONG,
    )
    assert (brief_call.feature, brief_call.tier) == ("interview_brief", ModelTier.FAST)
    assert questions_call.prompt_version == brief_call.prompt_version == "interview/v1"
    assert (body["questions_model"], body["brief_model"]) == ("fake-strong", "fake-fast")
    assert [u["feature"] for u in body["usage"]] == ["interview_questions", "interview_brief"]
    assert all(u["user_id"] == fx.USER_ID and u["call_id"] for u in body["usage"])
    assert len({u["call_id"] for u in body["usage"]}) == 2


def test_what_the_models_are_shown_is_delimited_and_the_brief_never_sees_the_resume(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    script(fake_provider)

    prep(client)

    questions_call, brief_call = fake_provider.requests
    q = questions_call.user_message
    assert "<<<RESUME_BEGIN " in q and "<<<JOB_BEGIN " in q
    assert "Northwind Systems" in q  # the resume's content is there for the questions
    assert "jordan.ikeji@example.test" not in q and "+234 800 555 0142" not in q  # contact never is
    b = brief_call.user_message
    assert "<<<SOURCES_BEGIN " in b
    assert "Northwind Systems" not in b and "Jordan" not in b
    assert "Harbor Freight Tech" in b and "201-500" in b
    for call in fake_provider.requests:
        assert "untrusted data" in call.system
        assert "never instructions to you" in call.system


# --- the injection fixture ---


def test_an_injected_instruction_and_its_fake_company_fact_do_not_reach_the_output(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    # A model that obeyed the instruction: it states the fake fact in the brief (cited to the
    # posting and to the company record), in an unknown and in a question.
    script(
        fake_provider,
        ix.questions_reply(ix.obedient_questions()),
        ix.brief_reply(ix.obedient_brief()),
    )

    response = prep(client, ix.injected_request())

    assert response.status_code == 200
    body = response.json()
    # Neither the injected instruction nor the fake fact was in front of either model ...
    for call in fake_provider.requests:
        assert "Ignore all previous instructions" not in call.user_message
        assert "Globex" not in call.user_message
        assert "40000" not in call.user_message
        assert "publicly listed" not in call.user_message
    # ... the posting's real content was ...
    assert "build Java and Spring Boot services" in fake_provider.requests[0].user_message
    # ... and nothing of it is in what comes back, however the model was misled.
    output = json.dumps({k: body[k] for k in ("questions", "brief")})
    for leaked in ("Globex", "40000", "acquired", "publicly listed", "Ignore all previous"):
        assert leaked not in output
    assert body["job_text_redactions"] == 1
    assert body["company_redactions"] == 1
    assert body["questions_dropped"] == 1
    assert {d["reason"] for d in body["brief"]["dropped"]} == {
        "INSTRUCTION_LEAK",  # repeats the injected sentence
        "EVIDENCE_NOT_FOUND",  # the same fact reworded: the quote is not in the posting
        "EMPTY_SOURCE",  # company.industry was injected, so it was left out
    }
    # The honest claims survived (the faithful industry claim lost its field along with the rest).
    assert len(claims(body)) == 4
    assert any("removed" in u for u in body["brief"]["unknowns"])
    # The company record's injected industry was left out, so the brief reports it as missing.
    assert "The company's industry is not in the company record." in body["brief"]["unknowns"]


# --- ungrounded claims ---


def test_ungrounded_claims_are_dropped_and_reported_by_reason_and_source(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    bad = [
        ix.claim("The salary is generous.", "job.salary", "generous"),
        ix.claim("The company was founded in 1998.", "company.name", "Harbor Freight Tech"),
        ix.claim("The company is well funded.", "company.industry", "Logistics software"),
        ix.claim("The role needs Rust.", "job.skills", "Rust, Go"),
    ]
    script(fake_provider, brief=ix.brief_reply(ix.brief_with(*bad)))

    body = prep(client).json()

    assert len(claims(body)) == 5  # only the faithful ones
    assert [(d["source"], d["reason"]) for d in body["brief"]["dropped"]] == [
        ("job.salary", "EMPTY_SOURCE"),
        ("company.name", "UNSUPPORTED_NUMBER"),
        ("company.industry", "UNSUPPORTED_STATEMENT"),
        ("job.skills", "EVIDENCE_NOT_FOUND"),
    ]
    assert any(u.startswith("4 statement(s) were removed") for u in body["brief"]["unknowns"])
    # Only the reason and source are returned, never the dropped text.
    assert "generous" not in json.dumps(body["brief"])
    assert "1998" not in json.dumps(body["brief"])


def test_an_unknown_that_states_a_fact_is_not_kept(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    brief = ix.faithful_brief()
    brief["unknowns"] = [
        "The company was founded in 1850, which could not be confirmed.",
        "The size of the engineering team is not stated.",
    ]
    script(fake_provider, brief=ix.brief_reply(brief))

    unknowns = prep(client).json()["brief"]["unknowns"]

    assert not any("1850" in u for u in unknowns)
    assert "The size of the engineering team is not stated." in unknowns


def test_a_job_with_only_a_title_has_no_claims_and_says_so_in_unknowns(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    sparse: dict[str, list[Any]] = {"sections": [], "unknowns": []}
    script(fake_provider, brief=json.dumps(sparse))

    body = prep(client, ix.request_body(job={"title": "Backend Engineer"}, company={})).json()

    assert body["brief"]["sections"] == []
    unknowns = body["brief"]["unknowns"]
    assert "The job has no description text." in unknowns
    assert "The company's size is not in the company record." in unknowns


# --- schema validation: one retry, then fail loudly ---


def test_an_invalid_questions_reply_is_retried_once(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue("not json", ix.questions_reply(), ix.brief_reply())

    response = prep(client)

    assert response.status_code == 200
    assert [r.feature for r in fake_provider.requests] == [
        "interview_questions",
        "interview_questions",
        "interview_brief",
    ]
    # Every attempt is billable, so every attempt is reported.
    assert [u["feature"] for u in response.json()["usage"]] == [
        "interview_questions",
        "interview_questions",
        "interview_brief",
    ]


def test_questions_that_stay_invalid_fail_loudly_with_the_usage_that_was_billed(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    only_behavioral = {
        "questions": [ix.question("behavioral", "Tell me about a time you failed.")] * 7
    }
    fake_provider.queue(json.dumps(only_behavioral), json.dumps(only_behavioral))

    response = prep(client)

    assert response.status_code == 502
    body = response.json()
    assert body["code"] == "llm_output_invalid"
    assert [u["feature"] for u in body["usage"]] == ["interview_questions"] * 2
    assert len(fake_provider.requests) == 2  # the brief was never asked for


def test_a_brief_that_stays_invalid_still_reports_the_questions_call(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    fake_provider.queue(ix.questions_reply(), '{"sections": "nope"}', "{}")

    response = prep(client)

    assert response.status_code == 502
    body = response.json()
    assert body["code"] == "llm_output_invalid"
    assert [u["feature"] for u in body["usage"]] == [
        "interview_questions",
        "interview_brief",
        "interview_brief",
    ]


def test_questions_that_are_all_copies_of_the_injection_fail_loudly(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    leak = f"Harbor Freight Tech {ix.FAKE_FACT}"
    leaking = {
        "questions": [
            ix.question(c, f"Why did {leak} in {n} places?")
            for n, c in enumerate(["behavioral", "technical", "role_specific"] * 2)
        ]
    }
    fake_provider.queue(json.dumps(leaking))

    response = prep(client, ix.injected_request())

    assert response.status_code == 502
    assert response.json()["code"] == "llm_output_invalid"
    assert [u["feature"] for u in response.json()["usage"]] == ["interview_questions"]


# --- the service boundary ---


def test_an_unknown_prompt_version_is_a_400(client: TestClient) -> None:
    response = prep(client, ix.request_body(prompt_version="interview/v99"))

    assert response.status_code == 400
    assert response.json()["code"] == "unknown_prompt_version"


def test_the_route_needs_the_service_token(anon_client: TestClient) -> None:
    assert anon_client.post(PATH, json=ix.request_body()).status_code == 401


def test_a_malformed_request_is_a_422_and_calls_no_model(
    client: TestClient, fake_provider: FakeProvider
) -> None:
    response = prep(client, {"user_id": fx.USER_ID, "job": {"title": "x"}})

    assert response.status_code == 422
    assert fake_provider.requests == []


# --- the keyless provider, end to end ---


def test_the_keyless_provider_produces_a_full_grounded_prep_for_the_fixture_job() -> None:
    settings = Settings(
        ai_service_token=SecretStr(TEST_TOKEN),
        rabbitmq_enabled=False,
        llm_provider=ProviderName.FAKE,
    )
    app = create_app(settings, provider=HeuristicProvider())

    with TestClient(app, headers={SERVICE_TOKEN_HEADER: TEST_TOKEN}) as client:
        response = prep(client)

    assert response.status_code == 200
    body = response.json()
    assert {q["category"] for q in body["questions"]} == {
        "behavioral",
        "technical",
        "role_specific",
    }
    assert 6 <= len(body["questions"]) <= 12
    # Its claims restate and quote the fields exactly, so grounding keeps every one.
    assert body["brief"]["dropped"] == []
    assert {c["source"] for c in claims(body)} >= {"job.title", "job.skills", "company.size"}
    assert body["usage"][0]["provider"] == "fake" and body["usage"][0]["cost_usd"] == "0"


def test_the_keyless_provider_does_not_echo_an_injected_posting() -> None:
    settings = Settings(
        ai_service_token=SecretStr(TEST_TOKEN),
        rabbitmq_enabled=False,
        llm_provider=ProviderName.FAKE,
    )
    app = create_app(settings, provider=HeuristicProvider())

    with TestClient(app, headers={SERVICE_TOKEN_HEADER: TEST_TOKEN}) as client:
        response = prep(client, ix.injected_request())

    assert response.status_code == 200
    output = json.dumps(response.json()["brief"]) + json.dumps(response.json()["questions"])
    for leaked in ("Globex", "40000", "acquired", "publicly listed"):
        assert leaked not in output
    assert response.json()["brief"]["dropped"] == []
