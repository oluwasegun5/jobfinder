import pytest

from app.prompts import load_named_prompt, load_prompt


def test_load_prompt_returns_versioned_text() -> None:
    prompt = load_prompt("diagnostics", 1)
    assert prompt.prompt_version == "diagnostics/v1"
    assert '{"status": "ok"}' in prompt.text


@pytest.mark.parametrize(("feature", "version"), [("../etc", 1), ("diagnostics", 0)])
def test_load_prompt_rejects_bad_references(feature: str, version: int) -> None:
    with pytest.raises(ValueError, match="Invalid prompt reference"):
        load_prompt(feature, version)


def test_load_named_prompt_reads_one_file_per_name_under_a_shared_version() -> None:
    questions = load_named_prompt("interview", "questions", 1)
    brief = load_named_prompt("interview", "brief", 1)

    assert questions.prompt_version == brief.prompt_version == "interview/v1"
    assert questions.text != brief.text


@pytest.mark.parametrize(
    ("feature", "name", "version"),
    [("../etc", "questions", 1), ("interview", "../questions", 1), ("interview", "brief", 0)],
)
def test_load_named_prompt_rejects_bad_references(feature: str, name: str, version: int) -> None:
    with pytest.raises(ValueError, match="Invalid prompt reference"):
        load_named_prompt(feature, name, version)
