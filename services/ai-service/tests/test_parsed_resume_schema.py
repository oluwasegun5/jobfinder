from typing import Any

import pytest
from pydantic import ValidationError

from app.parsing.schema import ParsedResume
from app.prompts import load_prompt


def _valid(**overrides: Any) -> dict[str, Any]:
    return {"contact": {"full_name": "Ada Example"}, "skills": ["Python"]} | overrides


def test_minimal_document_is_valid_and_defaults_are_filled() -> None:
    parsed = ParsedResume.model_validate_json("{}")
    assert parsed.schema_version == 1
    assert parsed.experience == []
    assert parsed.contact.links == []


def test_prompt_template_matches_the_schema() -> None:
    """The shape shown to the model must be one the validator accepts (guards prompt drift)."""
    text = load_prompt("parse_resume", 1).text
    template = text[text.index("{", text.index("## Output")) : text.rindex("}") + 1]
    ParsedResume.model_validate_json(template)


@pytest.mark.parametrize("field", ["score", "confidence", "verified", "match_score", "notes"])
def test_the_model_cannot_add_self_reported_fields(field: str) -> None:
    """Nothing in the schema lets the model describe or rate itself; extras are rejected."""
    with pytest.raises(ValidationError, match="extra_forbidden"):
        ParsedResume.model_validate(_valid(**{field: 100}))


def test_extra_fields_are_rejected_at_every_level() -> None:
    nested = _valid(
        experience=[{"company": "Acme", "title": "Dev", "verified": True}],
    )
    with pytest.raises(ValidationError, match="extra_forbidden"):
        ParsedResume.model_validate(nested)
    with pytest.raises(ValidationError, match="extra_forbidden"):
        ParsedResume.model_validate(_valid(contact={"full_name": "A", "trusted": True}))


def test_schema_version_is_fixed() -> None:
    with pytest.raises(ValidationError):
        ParsedResume.model_validate(_valid(schema_version=2))


def test_types_are_strict() -> None:
    with pytest.raises(ValidationError):
        ParsedResume.model_validate(
            _valid(experience=[{"company": "Acme", "title": "Dev", "is_current": "yes"}])
        )
    with pytest.raises(ValidationError):
        ParsedResume.model_validate(_valid(skills="Python"))


@pytest.mark.parametrize("date", ["2021", "2021-06"])
def test_partial_iso_dates_are_accepted(date: str) -> None:
    ParsedResume.model_validate(
        _valid(experience=[{"company": "A", "title": "B", "start_date": date}])
    )


@pytest.mark.parametrize("date", ["June 2021", "2021-13", "21", "2021-6", "present"])
def test_other_date_formats_are_rejected(date: str) -> None:
    with pytest.raises(ValidationError):
        ParsedResume.model_validate(
            _valid(experience=[{"company": "A", "title": "B", "start_date": date}])
        )


def test_a_current_role_has_no_end_date() -> None:
    with pytest.raises(ValidationError, match="current role"):
        ParsedResume.model_validate(
            _valid(
                experience=[
                    {
                        "company": "A",
                        "title": "B",
                        "start_date": "2020",
                        "end_date": "2021",
                        "is_current": True,
                    }
                ]
            )
        )


@pytest.mark.parametrize(("start", "end"), [("2021", "2020"), ("2021-06", "2021-03")])
def test_periods_cannot_end_before_they_start(start: str, end: str) -> None:
    with pytest.raises(ValidationError, match="before start_date"):
        ParsedResume.model_validate(
            _valid(education=[{"institution": "U", "start_date": start, "end_date": end}])
        )


def test_mixed_precision_periods_compare_sensibly() -> None:
    ParsedResume.model_validate(
        _valid(education=[{"institution": "U", "start_date": "2020-06", "end_date": "2020"}])
    )


@pytest.mark.parametrize(
    "url",
    [
        "javascript:alert(1)",
        "JAVASCRIPT:alert(1)",
        "data:text/html;base64,PHNjcmlwdD4=",
        "ftp://example.test/cv",
        "//evil.test",
        "https://ex ample.test",
    ],
)
def test_links_must_be_plain_http_urls(url: str) -> None:
    with pytest.raises(ValidationError):
        ParsedResume.model_validate(_valid(contact={"links": [{"url": url}]}))
    with pytest.raises(ValidationError):
        ParsedResume.model_validate(_valid(projects=[{"name": "P", "url": url}]))


def test_http_and_https_links_are_accepted() -> None:
    ParsedResume.model_validate(
        _valid(contact={"links": [{"url": "http://a.test"}, {"url": "https://b.test/x?y=1"}]})
    )


def test_email_must_look_like_an_email() -> None:
    with pytest.raises(ValidationError, match="not an email"):
        ParsedResume.model_validate(_valid(contact={"email": "not an email"}))


def test_control_characters_are_stripped_and_blank_means_missing() -> None:
    parsed = ParsedResume.model_validate(
        _valid(
            contact={"full_name": "Ada\x00 \x1bLovelace\u0085", "phone": "   ", "location": ""},
            headline="",
        )
    )
    assert parsed.contact.full_name == "Ada   Lovelace"  # NUL and ESC each became a space
    assert parsed.contact.phone is None
    assert parsed.contact.location is None
    assert parsed.headline is None


def test_skills_are_deduplicated_case_insensitively_keeping_first_spelling() -> None:
    parsed = ParsedResume.model_validate(_valid(skills=["Python", "python", "SQL", "PYTHON"]))
    assert parsed.skills == ["Python", "SQL"]


def test_lists_and_strings_are_bounded() -> None:
    with pytest.raises(ValidationError, match="too_long"):
        ParsedResume.model_validate(_valid(skills=[f"skill{i}" for i in range(101)]))
    with pytest.raises(ValidationError, match="string_too_long"):
        ParsedResume.model_validate(_valid(summary="x" * 2001))
    with pytest.raises(ValidationError, match="too_short"):
        ParsedResume.model_validate(_valid(skills=[""]))
