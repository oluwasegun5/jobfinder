import pytest

from app.prompts import load_prompt


def test_load_prompt_returns_versioned_text() -> None:
    prompt = load_prompt("diagnostics", 1)
    assert prompt.prompt_version == "diagnostics/v1"
    assert '{"status": "ok"}' in prompt.text


@pytest.mark.parametrize(("feature", "version"), [("../etc", 1), ("diagnostics", 0)])
def test_load_prompt_rejects_bad_references(feature: str, version: int) -> None:
    with pytest.raises(ValueError, match="Invalid prompt reference"):
        load_prompt(feature, version)
