"""POST /v1/fact-check-text: the prose-only check core-api re-runs on every edit and on approval."""

from typing import Any

from fastapi.testclient import TestClient

from tests.fixtures import tailoring as fx

PATH = "/v1/fact-check-text"


def check(client: TestClient, *texts: str, **extra: Any) -> dict[str, Any]:
    body = {
        "source": fx.SOURCE,
        "texts": [{"path": f"paragraphs[{i}]", "text": t} for i, t in enumerate(texts)],
        "as_of": "2026-10-01",
        **extra,
    }
    response = client.post(PATH, json=body)
    assert response.status_code == 200
    result: dict[str, Any] = response.json()
    return result


def codes(result: dict[str, Any]) -> set[str]:
    return {f["code"] for f in result["flags"]}


def test_clean_prose_passes(client: TestClient) -> None:
    result = check(
        client, "At Northwind Systems I led a team of 4 engineers delivering a billing platform."
    )

    assert result["passed"] is True
    assert result["flags"] == []
    assert result["checker_version"] == "fact_check/v1"


def test_placeholders_are_blocking_in_every_form(client: TestClient) -> None:
    for text in (
        "Sincerely, [Your Name]",
        "Dear {{recipient}}, I write about Java work at Northwind Systems.",
        "Call <phone> about the Java role at Northwind Systems.",
        "NEEDS_INPUT",
        "Dear Hiring Manager Name, I am a Java engineer at Northwind Systems.",
    ):
        result = check(client, text)
        assert "PLACEHOLDER" in codes(result), text
        assert result["passed"] is False, text


def test_invented_experience_years_are_blocking_and_supported_ones_are_not(
    client: TestClient,
) -> None:
    assert "NEW_EXPERIENCE_YEARS" in codes(check(client, "I have 25 years of experience."))
    assert "NEW_EXPERIENCE_YEARS" in codes(check(client, "Over twenty years in Java."))
    assert "NEW_EXPERIENCE_YEARS" not in codes(check(client, "I have six years of experience."))
    # Role lengths and the whole career, from the resume's dates (as of 2026-10): 9 and 5.
    assert "NEW_EXPERIENCE_YEARS" not in codes(check(client, "Nine years of experience in total."))
    assert "NEW_EXPERIENCE_YEARS" not in codes(
        check(client, "Almost 4 years of experience at Brightpath.")
    )


def test_the_profile_years_and_the_users_own_words_support_a_claim(client: TestClient) -> None:
    assert "NEW_EXPERIENCE_YEARS" in codes(check(client, "I have 12 years of experience."))
    assert "NEW_EXPERIENCE_YEARS" not in codes(
        check(client, "I have 12 years of experience.", years_experience=12)
    )
    assert "NEW_EXPERIENCE_YEARS" not in codes(
        check(
            client,
            "I have 12 years of experience.",
            allowed_context="I have 12 years of experience",
        )
    )


def test_the_job_company_is_allowed_as_a_name_but_not_as_an_employer(client: TestClient) -> None:
    context = "Backend Engineer Harbor Freight Tech"

    named = check(client, "I admire Harbor Freight Tech.", allowed_context=context)
    claimed = check(client, "I joined Harbor Freight Tech last year.", allowed_context=context)

    assert "NEW_TERM" not in codes(named)
    assert "NEW_EMPLOYER" in codes(claimed)
    assert claimed["passed"] is False


def test_an_injected_sentence_and_the_redaction_marker_are_leaks(client: TestClient) -> None:
    job = fx.JOB_WITH_INJECTION["description"]

    leaked = check(client, f"Thank you. {fx.INJECTION}", job_description=job)
    marker = check(client, "I like [removed: instruction-like text] a lot.")

    assert "INJECTION_LEAKAGE" in codes(leaked)
    assert "INJECTION_LEAKAGE" in codes(marker)


def test_bad_requests_are_rejected(client: TestClient) -> None:
    assert client.post(PATH, json={"source": fx.SOURCE, "texts": []}).status_code == 422
    too_long = {"path": "p", "text": "x" * 4001}
    assert client.post(PATH, json={"source": fx.SOURCE, "texts": [too_long]}).status_code == 422
    assert client.post(PATH, json={"texts": [{"path": "p", "text": "t"}]}).status_code == 422


def test_it_needs_the_service_token(anon_client: TestClient) -> None:
    body = {"source": fx.SOURCE, "texts": [{"path": "p", "text": "t"}]}
    assert anon_client.post(PATH, json=body).status_code in {401, 403}
