from app.parsing.grounding import check_grounding
from app.parsing.schema import ParsedResume

TEXT = "Ada Example. Senior Engineer at Analytical Engines Ltd. Skills: Node.js, C++, Postgres SQL."


def _resume(**parts: object) -> ParsedResume:
    return ParsedResume.model_validate(parts)


def test_grounded_content_passes_untouched() -> None:
    resume = _resume(
        skills=["Node.js", "C++"],
        experience=[{"company": "Analytical Engines Ltd", "title": "Senior Engineer"}],
    )
    checked, warnings = check_grounding(resume, TEXT)
    assert checked == resume
    assert warnings == []


def test_matching_ignores_case_spacing_and_punctuation() -> None:
    resume = _resume(skills=["NODEJS", "postgres  sql"])
    checked, warnings = check_grounding(resume, TEXT)
    assert checked.skills == ["NODEJS", "postgres  sql"]
    assert warnings == []


def test_ungrounded_skills_are_removed_and_reported_by_position() -> None:
    resume = _resume(skills=["Node.js", "Haskell", "C++", "Rust"])
    checked, warnings = check_grounding(resume, TEXT)
    assert checked.skills == ["Node.js", "C++"]
    assert [(w.path, w.code) for w in warnings] == [
        ("skills[1]", "skill_not_in_source"),
        ("skills[3]", "skill_not_in_source"),
    ]


def test_ungrounded_entities_are_flagged_but_kept() -> None:
    resume = _resume(
        experience=[{"company": "Initech", "title": "CEO"}],
        education=[{"institution": "Hogwarts"}],
        projects=[{"name": "Skynet"}],
    )
    checked, warnings = check_grounding(resume, TEXT)
    assert checked.experience == resume.experience
    assert checked.education == resume.education
    assert checked.projects == resume.projects
    assert {w.path for w in warnings} == {
        "experience[0].company",
        "education[0].institution",
        "projects[0].name",
    }


def test_the_input_is_not_mutated() -> None:
    resume = _resume(skills=["Haskell"])
    check_grounding(resume, TEXT)
    assert resume.skills == ["Haskell"]
