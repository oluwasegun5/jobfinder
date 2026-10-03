"""Shapes of interview prep: what the request accepts and what a model's output must look like."""

import json
from typing import Any

import pytest
from pydantic import ValidationError

from app.interview.schema import (
    InterviewPrepRequest,
    LlmBrief,
    LlmQuestions,
)
from app.interview.service import resolve_prompts
from app.matching.service import UnknownPromptVersionError
from tests.fixtures import interview as ix


def parse_questions(body: dict[str, Any]) -> LlmQuestions:
    return LlmQuestions.model_validate_json(json.dumps(body))


def test_a_faithful_questions_reply_is_valid() -> None:
    assert len(parse_questions(ix.faithful_questions()).questions) == 9


def test_questions_must_use_all_three_categories() -> None:
    body = ix.faithful_questions()
    body["questions"] = [q for q in body["questions"] if q["category"] != "technical"]
    body["questions"] += [ix.question("behavioral", "Tell me about a time you were wrong.")] * 3

    with pytest.raises(ValidationError, match="technical"):
        parse_questions(body)


def test_questions_have_a_minimum_count() -> None:
    body = {"questions": ix.faithful_questions()["questions"][:5]}

    with pytest.raises(ValidationError):
        parse_questions(body)


@pytest.mark.parametrize(
    "text",
    [
        "How would you use **Kafka** here?",
        "Tell me about your time at [Company] and what you did.",
        "How do you use `kubectl` day to day in this job?",
        "Describe <name> and how they shaped your work.",
    ],
)
def test_questions_are_plain_text_without_markdown_or_placeholders(text: str) -> None:
    body = ix.faithful_questions()
    body["questions"][0]["question"] = text

    with pytest.raises(ValidationError):
        parse_questions(body)


def test_a_question_cannot_carry_extra_fields_or_unknown_categories() -> None:
    extra = ix.faithful_questions()
    extra["questions"][0]["trusted"] = True
    unknown = ix.faithful_questions()
    unknown["questions"][0]["category"] = "salary"

    for body in (extra, unknown):
        with pytest.raises(ValidationError):
            parse_questions(body)


def test_a_brief_claim_must_name_a_known_source_field() -> None:
    body = ix.brief_with(ix.claim("The company is large.", "company.revenue", "large"))

    with pytest.raises(ValidationError, match=r"company\.revenue"):
        LlmBrief.model_validate_json(json.dumps(body))


def test_a_brief_has_no_field_for_the_model_to_vouch_for_itself() -> None:
    body = ix.faithful_brief()
    body["confidence"] = 0.99

    with pytest.raises(ValidationError):
        LlmBrief.model_validate_json(json.dumps(body))


def test_the_request_bounds_the_count_and_the_prompt_version() -> None:
    for extra in ({"question_count": 3}, {"question_count": 40}, {"prompt_version": "interview/x"}):
        with pytest.raises(ValidationError):
            InterviewPrepRequest.model_validate(ix.request_body(**extra))
    request = InterviewPrepRequest.model_validate(ix.request_body())
    assert request.prompt_version == "interview/v1"
    assert request.question_count == 12


def test_the_request_accepts_a_job_with_nothing_but_a_title() -> None:
    body = ix.request_body(job={"title": "Backend Engineer"}, company={})

    request = InterviewPrepRequest.model_validate(body)

    assert request.job.description is None
    assert request.company.name is None


def test_prompt_version_v1_is_both_prompt_files() -> None:
    questions, brief = resolve_prompts("interview/v1")

    assert questions.prompt_version == brief.prompt_version == "interview/v1"
    assert questions.text != brief.text
    assert "questions" in questions.text.casefold()
    assert "evidence" in brief.text


def test_an_unknown_prompt_version_is_refused() -> None:
    with pytest.raises(UnknownPromptVersionError):
        resolve_prompts("interview/v99")
