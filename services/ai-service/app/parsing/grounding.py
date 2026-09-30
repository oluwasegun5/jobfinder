"""Independent check that model output is anchored in the CV text.

Schema validation proves shape, not truth. This looks at the extracted text itself and reports
(or, for skills, removes) content the CV never contains, so a model that hallucinated or was
talked into "adding" things cannot put them in a profile unnoticed. It is deliberately
forgiving about case, spacing and punctuation ("Node.js" vs "NodeJS") because PDF extraction
mangles those.
"""

import re
import unicodedata
from typing import Literal

from pydantic import BaseModel, ConfigDict

from app.parsing.schema import ParsedResume

_NON_ALNUM = re.compile(r"[\W_]+")


class ParseWarning(BaseModel):
    model_config = ConfigDict(frozen=True)

    path: str
    code: Literal["skill_not_in_source", "not_in_source"]


def _squash(value: str) -> str:
    return _NON_ALNUM.sub("", unicodedata.normalize("NFKC", value).casefold())


def check_grounding(
    parsed: ParsedResume, source_text: str
) -> tuple[ParsedResume, list[ParseWarning]]:
    """Returns the resume without ungrounded skills, plus a warning per ungrounded item.

    Skills are dropped (they feed matching, so an invented one does harm). Employers, schools
    and projects are only flagged: extraction noise can garble a real name, and losing a real
    job is worse than showing the user a warning to review.
    """
    haystack = _squash(source_text)
    warnings: list[ParseWarning] = []

    def grounded(value: str) -> bool:
        needle = _squash(value)
        return bool(needle) and needle in haystack

    skills: list[str] = []
    for i, skill in enumerate(parsed.skills):
        if grounded(skill):
            skills.append(skill)
        else:
            warnings.append(ParseWarning(path=f"skills[{i}]", code="skill_not_in_source"))

    for i, job in enumerate(parsed.experience):
        if not grounded(job.company):
            warnings.append(ParseWarning(path=f"experience[{i}].company", code="not_in_source"))
    for i, school in enumerate(parsed.education):
        if not grounded(school.institution):
            warnings.append(ParseWarning(path=f"education[{i}].institution", code="not_in_source"))
    for i, project in enumerate(parsed.projects):
        if not grounded(project.name):
            warnings.append(ParseWarning(path=f"projects[{i}].name", code="not_in_source"))

    return parsed.model_copy(update={"skills": skills}), warnings
