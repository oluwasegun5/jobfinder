"""The deterministic fact check (PLAN.md section 7: "a post-generation fact check step that
compares entities against the source and flags anything new").

`check_resume(source, candidate, job_description)` compares a candidate resume (a model's tailored
output, or a user's edit of it) with the source resume the user actually uploaded, and returns a
flag for every entity the candidate has that the source does not:

* BLOCKING: an invented employer, job title, institution, degree, certification, project, date
  range, link, email or phone number, a changed contact block, and instruction text from a job
  description that leaked into the output. A document with a BLOCKING flag must not be approved.
* WARNING: a new skill, metric, number, year or unfamiliar proper noun, and a long verbatim copy
  of the job description. The user is shown these and decides.

It has no model behind it and costs nothing: the same inputs always give the same flags, which is
what lets core-api re-run it on every edit and again when a document is approved. It compares
structured fields (employers, titles, dates, schools, skills) and also reads the free text
(summary, bullets, project descriptions) for the same kinds of entity.
"""

import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date
from enum import StrEnum

from pydantic import BaseModel, ConfigDict

from app.factcheck import injection, lexicon, normalize
from app.parsing.schema import ParsedResume

CHECKER_VERSION = "fact_check/v1"
_VALUE_CHARS = 200


class Severity(StrEnum):
    BLOCKING = "BLOCKING"
    WARNING = "WARNING"


class FlagCode(StrEnum):
    NEW_EMPLOYER = "NEW_EMPLOYER"
    NEW_JOB_TITLE = "NEW_JOB_TITLE"
    NEW_INSTITUTION = "NEW_INSTITUTION"
    NEW_DEGREE = "NEW_DEGREE"
    NEW_CERTIFICATION = "NEW_CERTIFICATION"
    NEW_DATE_RANGE = "NEW_DATE_RANGE"
    NEW_PROJECT = "NEW_PROJECT"
    NEW_URL = "NEW_URL"
    NEW_EMAIL = "NEW_EMAIL"
    NEW_PHONE = "NEW_PHONE"
    CONTACT_CHANGED = "CONTACT_CHANGED"
    INJECTION_LEAKAGE = "INJECTION_LEAKAGE"
    # Raised for generated prose (cover letters, screening answers), see `check_texts`.
    PLACEHOLDER = "PLACEHOLDER"
    NEW_EXPERIENCE_YEARS = "NEW_EXPERIENCE_YEARS"
    NEW_SKILL = "NEW_SKILL"
    NEW_METRIC = "NEW_METRIC"
    NEW_NUMBER = "NEW_NUMBER"
    NEW_YEAR = "NEW_YEAR"
    NEW_TERM = "NEW_TERM"
    ENTRY_REMOVED = "ENTRY_REMOVED"
    CHANGED_FIELD = "CHANGED_FIELD"
    JOB_TEXT_COPIED = "JOB_TEXT_COPIED"
    # Raised by the tailoring pipeline, not by the comparison: the job text contained instructions.
    JOB_DESCRIPTION_INJECTION = "JOB_DESCRIPTION_INJECTION"


SEVERITY: dict[FlagCode, Severity] = {
    **dict.fromkeys(
        (
            FlagCode.NEW_EMPLOYER,
            FlagCode.NEW_JOB_TITLE,
            FlagCode.NEW_INSTITUTION,
            FlagCode.NEW_DEGREE,
            FlagCode.NEW_CERTIFICATION,
            FlagCode.NEW_DATE_RANGE,
            FlagCode.NEW_PROJECT,
            FlagCode.NEW_URL,
            FlagCode.NEW_EMAIL,
            FlagCode.NEW_PHONE,
            FlagCode.CONTACT_CHANGED,
            FlagCode.INJECTION_LEAKAGE,
            FlagCode.PLACEHOLDER,
            FlagCode.NEW_EXPERIENCE_YEARS,
        ),
        Severity.BLOCKING,
    ),
    **dict.fromkeys(
        (
            FlagCode.NEW_SKILL,
            FlagCode.NEW_METRIC,
            FlagCode.NEW_NUMBER,
            FlagCode.NEW_YEAR,
            FlagCode.NEW_TERM,
            FlagCode.ENTRY_REMOVED,
            FlagCode.CHANGED_FIELD,
            FlagCode.JOB_TEXT_COPIED,
            FlagCode.JOB_DESCRIPTION_INJECTION,
        ),
        Severity.WARNING,
    ),
}

_MESSAGES: dict[FlagCode, str] = {
    FlagCode.NEW_EMPLOYER: "This employer is not in your resume.",
    FlagCode.NEW_JOB_TITLE: "This job title is not in your resume.",
    FlagCode.NEW_INSTITUTION: "This school is not in your resume.",
    FlagCode.NEW_DEGREE: "This degree is not in your resume.",
    FlagCode.NEW_CERTIFICATION: "This certification is not in your resume.",
    FlagCode.NEW_DATE_RANGE: "These dates are not the ones in your resume.",
    FlagCode.NEW_PROJECT: "This project is not in your resume.",
    FlagCode.NEW_URL: "This link is not in your resume.",
    FlagCode.NEW_EMAIL: "This email address is not in your resume.",
    FlagCode.NEW_PHONE: "This phone number is not in your resume.",
    FlagCode.CONTACT_CHANGED: "Your contact details differ from your resume.",
    FlagCode.INJECTION_LEAKAGE: "This text looks like an instruction from the job posting.",
    FlagCode.PLACEHOLDER: "This text still contains a placeholder that must be filled in or removed.",  # noqa: E501
    FlagCode.NEW_EXPERIENCE_YEARS: "This length of experience does not match your resume.",
    FlagCode.NEW_SKILL: "This skill or technology is not in your resume.",
    FlagCode.NEW_METRIC: "This figure is not in your resume.",
    FlagCode.NEW_NUMBER: "This number is not in your resume.",
    FlagCode.NEW_YEAR: "This year is not in your resume.",
    FlagCode.NEW_TERM: "This name does not appear in your resume.",
    FlagCode.ENTRY_REMOVED: "This entry of your resume is missing.",
    FlagCode.CHANGED_FIELD: "This detail differs from your resume.",
    FlagCode.JOB_TEXT_COPIED: "This text is copied from the job posting.",
    FlagCode.JOB_DESCRIPTION_INJECTION: "Instructions in the job posting were ignored.",
}


class FactFlag(BaseModel):
    """One entity the candidate has and the source does not. `path` is in the candidate's shape."""

    model_config = ConfigDict(frozen=True)

    code: FlagCode
    severity: Severity
    path: str | None
    value: str
    message: str


class FactCheckResult(BaseModel):
    model_config = ConfigDict(frozen=True)

    # True when there is no BLOCKING flag; warnings do not stop an approval.
    passed: bool
    blocking: int
    warnings: int
    flags: list[FactFlag]
    checker_version: str = CHECKER_VERSION


# --- patterns ------------------------------------------------------------------------

_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_URL = re.compile(
    r"(?:https?://|www\.)[^\s<>\"')\]]+"
    r"|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|io|dev|org|me|app|ai|co|xyz|ng)\b(?:/[^\s<>\"')\]]*)?",
    re.IGNORECASE,
)
_PHONE = re.compile(r"(?<![\w.])\+?\d[\d\s().-]{7,}\d(?!\w)")
_MONTH = r"(?:(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+)?"
_RANGE = re.compile(
    rf"\b{_MONTH}((?:19|20)\d\d)\s*(?:-|\u2013|\u2014|to|until)\s*{_MONTH}((?:19|20)\d\d|present|now|current)\b",
    re.IGNORECASE,
)
_NUM_TOKEN = re.compile(
    r"(?<![\w.])(?P<cur>[$€£₦])?\s?(?P<num>\d[\d,]*(?:\.\d+)?)\s?"
    r"(?P<suf>%|percent\b|x\b|k\b|m\b|mm\b|bn\b|million\b|billion\b|thousand\b|\+)?",
    re.IGNORECASE,
)
_EMPLOYER_CUE = re.compile(
    r"(?:\b(?i:worked\s+(?:at|for)|employed\s+(?:at|by)|working\s+(?:at|for)|joined|"
    r"previously\s+(?:at|with)|formerly\s+(?:at|with)|"
    r"spent\s+(?:\w+\s+){0,3}?years?\s+(?:at|with|for)|"
    r"(?:was|am|as)\s+(?:an?\s+)?(?:\w+\s+){0,3}?(?:engineer|developer|manager|lead|consultant|"
    r"analyst|architect|director|intern|officer)\s+(?:at|for|with))|@)\s*"
    r"((?:[A-Z][\w&'.-]*)(?:\s+(?:[A-Z][\w&'.-]*|&|of|and))*)"
)
_DEGREE_CLAIM = re.compile(
    r"\b(?:ph\.?\s?d|doctorate|doctoral|mba|m\.?sc|b\.?sc|master'?s\s+(?:degree|of|in)|"
    r"bachelor'?s?(?:\s+degree|\s+of|\s+in)?|diploma\s+in|associate'?s\s+degree)\b",
    re.IGNORECASE,
)
_CERT_ACRONYM = re.compile(
    r"\b(?:PMP|CISSP|CISM|CISA|CPA|CFA|CCNA|CCNP|CEH|OSCP|CKA|CKAD|CKS|CSM|PSM|ITIL|PRINCE2|"
    r"TOGAF|CAPM)\b"
)
_CERT_PHRASE = re.compile(
    r"(?:\b[A-Z][\w+#.&-]*\s+)*(?i:certified|certification|certificate)"
    r"(?:(?:\s+[A-Z][\w+#.&-]*){1,4}|\s+(?i:in|as|for|on)\s+[\w+#.]+)?"
)
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?;:])\s+|\n+")
_CAPITALISED = re.compile(r"[A-Z][a-z][A-Za-z0-9+#&'-]*")
_COMMON_CAPS = frozenset(
    {
        "january",
        "february",
        "march",
        "april",
        "june",
        "july",
        "august",
        "september",
        "october",
        "november",
        "december",
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
        "sunday",
        "english",
        "africa",
        "europe",
        "asia",
        "america",
    }
)


def _clip(value: str) -> str:
    value = " ".join(value.split())
    return value if len(value) <= _VALUE_CHARS else value[: _VALUE_CHARS - 1] + "…"


# --- the source, indexed -------------------------------------------------------------


def _strings(resume: ParsedResume) -> list[tuple[str, str]]:
    """Every text of a resume with its path, in reading order."""
    out: list[tuple[str, str]] = []

    def add(path: str, value: str | None) -> None:
        if value:
            out.append((path, value))

    c = resume.contact
    add("contact.full_name", c.full_name)
    add("contact.email", c.email)
    add("contact.phone", c.phone)
    add("contact.location", c.location)
    for i, link in enumerate(c.links):
        add(f"contact.links[{i}].label", link.label)
        add(f"contact.links[{i}].url", link.url)
    add("headline", resume.headline)
    add("summary", resume.summary)
    for i, job in enumerate(resume.experience):
        p = f"experience[{i}]"
        add(f"{p}.company", job.company)
        add(f"{p}.title", job.title)
        add(f"{p}.location", job.location)
        add(f"{p}.start_date", job.start_date)
        add(f"{p}.end_date", job.end_date)
        for j, bullet in enumerate(job.bullets):
            add(f"{p}.bullets[{j}]", bullet)
    for i, edu in enumerate(resume.education):
        p = f"education[{i}]"
        add(f"{p}.institution", edu.institution)
        add(f"{p}.degree", edu.degree)
        add(f"{p}.field_of_study", edu.field_of_study)
        add(f"{p}.start_date", edu.start_date)
        add(f"{p}.end_date", edu.end_date)
    for i, skill in enumerate(resume.skills):
        add(f"skills[{i}]", skill)
    for i, project in enumerate(resume.projects):
        p = f"projects[{i}]"
        add(f"{p}.name", project.name)
        add(f"{p}.description", project.description)
        add(f"{p}.url", project.url)
        for j, tech in enumerate(project.technologies):
            add(f"{p}.technologies[{j}]", tech)
    for i, cert in enumerate(resume.certifications):
        p = f"certifications[{i}]"
        add(f"{p}.name", cert.name)
        add(f"{p}.issuer", cert.issuer)
        add(f"{p}.date", cert.date)
    return out


def _range_key(start: str | None, end: str | None, current: bool) -> tuple[str, str]:
    return (start[:4] if start else "", "present" if current or not end else end[:4])


@dataclass(slots=True)
class _Source:
    resume: ParsedResume
    text: str
    words: set[str]
    compact_text: str
    # Compact text of the resume alone (`compact_text` also holds the allowed extra context).
    resume_compact: str
    numbers: set[str]
    years: set[str]
    urls: set[str]
    emails: set[str]
    phones: set[str]
    skill_keys: set[str]
    degree_levels: set[str]
    ranges: set[tuple[str, str]]
    shingles: set[tuple[str, ...]] = field(default_factory=set)

    @classmethod
    def of(cls, resume: ParsedResume, extra_text: str = "") -> "_Source":
        pairs = _strings(resume)
        resume_text = "\n".join(value for _, value in pairs)
        text = f"{resume_text}\n{extra_text}" if extra_text else resume_text
        urls = {normalize.norm_url(m.group(0)) for m in _URL.finditer(_EMAIL.sub(" ", text))}
        urls |= {normalize.norm_url(link.url) for link in resume.contact.links}
        urls |= {normalize.norm_url(p.url) for p in resume.projects if p.url}
        skill_keys = {normalize.canonical_skill(s) for s in resume.skills}
        for project in resume.projects:
            skill_keys |= {normalize.canonical_skill(t) for t in project.technologies}
        skill_keys |= set(lexicon.skills_in_text(text))
        levels = normalize.degree_levels_in(text)
        for edu in resume.education:
            level = normalize.parse_degree(edu.degree, edu.field_of_study).level
            if level:
                levels.add(level)
        ranges = {_range_key(j.start_date, j.end_date, j.is_current) for j in resume.experience} | {
            _range_key(e.start_date, e.end_date, False) for e in resume.education
        }
        ranges |= {(m.group(1), m.group(2).casefold()) for m in _RANGE.finditer(text)}
        tokens = normalize.words(text)
        return cls(
            resume=resume,
            text=text,
            words=set(tokens),
            compact_text=normalize.compact(text),
            resume_compact=normalize.compact(resume_text),
            numbers=set(normalize.numbers_in(text)),
            years=normalize.years_in(text),
            urls=urls,
            emails={normalize.norm_email(m.group(0)) for m in _EMAIL.finditer(text)},
            phones={normalize.norm_phone(m.group(0)) for m in _PHONE.finditer(text)},
            skill_keys=skill_keys,
            degree_levels=levels,
            ranges=ranges,
            shingles={tuple(tokens[i : i + 8]) for i in range(len(tokens) - 7)},
        )

    def knows_word(self, token: str) -> bool:
        t = normalize.fold(token)
        if t in self.words:
            return True
        for w in self.words:
            if len(min(t, w, key=len)) >= 4 and (t.startswith(w) or w.startswith(t)):
                return True
        return False

    def has_text(self, value: str) -> bool:
        key = normalize.compact(value)
        return bool(key) and key in self.compact_text

    def has_resume_text(self, value: str) -> bool:
        """Like `has_text`, but only the resume counts (a job's company is not an employer)."""
        key = normalize.compact(value)
        return bool(key) and key in self.resume_compact


class _Flags:
    def __init__(self) -> None:
        self._seen: set[tuple[FlagCode, str | None, str]] = set()
        self.items: list[FactFlag] = []

    def add(self, code: FlagCode, path: str | None, value: str) -> None:
        value = _clip(value)
        key = (code, path, value)
        if key in self._seen:
            return
        self._seen.add(key)
        self.items.append(
            FactFlag(
                code=code, severity=SEVERITY[code], path=path, value=value, message=_MESSAGES[code]
            )
        )


# --- free text -----------------------------------------------------------------------


def _check_text(text: str, path: str, src: _Source, flags: _Flags) -> None:
    """Entities a free-text field mentions that the source does not."""
    remaining = text
    for match in _EMAIL.finditer(text):
        if normalize.norm_email(match.group(0)) not in src.emails:
            flags.add(FlagCode.NEW_EMAIL, path, match.group(0))
    remaining = _EMAIL.sub(" ", remaining)
    for match in _URL.finditer(remaining):
        if normalize.norm_url(match.group(0)) not in src.urls:
            flags.add(FlagCode.NEW_URL, path, match.group(0))
    remaining = _URL.sub(" ", remaining)
    for match in _RANGE.finditer(remaining):
        key = (match.group(1), match.group(2).casefold())
        if key not in src.ranges:
            flags.add(FlagCode.NEW_DATE_RANGE, path, match.group(0))
    remaining = _RANGE.sub(" ", remaining)
    for match in _PHONE.finditer(remaining):
        digits = normalize.norm_phone(match.group(0))
        if 9 <= len(digits) <= 15 and digits not in src.phones:
            flags.add(FlagCode.NEW_PHONE, path, match.group(0))
    remaining = _PHONE.sub(" ", remaining)
    for year in sorted(normalize.years_in(remaining) - src.years):
        flags.add(FlagCode.NEW_YEAR, path, year)
    remaining = normalize.strip_years(remaining)

    for match in _NUM_TOKEN.finditer(remaining):
        value = match.group("num").replace(",", "")
        metric = bool(match.group("cur") or match.group("suf"))
        if value not in src.numbers:
            code = FlagCode.NEW_METRIC if metric else FlagCode.NEW_NUMBER
            flags.add(code, path, match.group(0).strip())
    for word in normalize.numbers_in(re.sub(r"\d[\d,.]*", " ", remaining)):
        if word not in src.numbers:
            flags.add(FlagCode.NEW_NUMBER, path, word)

    for match in _DEGREE_CLAIM.finditer(text):
        if normalize.degree_levels_in(match.group(0)) - src.degree_levels:
            flags.add(FlagCode.NEW_DEGREE, path, match.group(0))
    for match in _CERT_ACRONYM.finditer(text):
        if match.group(0).casefold() not in src.words:
            flags.add(FlagCode.NEW_CERTIFICATION, path, match.group(0))
    for match in _CERT_PHRASE.finditer(text):
        if not src.has_text(match.group(0)):
            flags.add(FlagCode.NEW_CERTIFICATION, path, match.group(0))

    for match in _EMPLOYER_CUE.finditer(text):
        span = re.sub(r"(?:\s+(?:of|and|&))+$", "", match.group(1)).strip()
        if (
            span
            and not src.has_resume_text(span)
            and normalize.canonical_skill(span) not in lexicon.KNOWN_SKILLS
        ):
            flags.add(FlagCode.NEW_EMPLOYER, path, span)

    for skill_key, written in lexicon.skills_in_text(text).items():
        if skill_key not in src.skill_keys:
            flags.add(FlagCode.NEW_SKILL, path, written)

    for sentence in _SENTENCE_SPLIT.split(text):
        tokens = sentence.split()
        for token in tokens[1:]:
            word = token.strip(".,;:!?()[]{}\"'")
            if not _CAPITALISED.fullmatch(word) or normalize.fold(word) in _COMMON_CAPS:
                continue
            if lexicon.skills_in_text(word) or src.knows_word(word):
                continue
            flags.add(FlagCode.NEW_TERM, path, word)


# --- structure -----------------------------------------------------------------------


def _dates_supported(
    src: tuple[str | None, str | None, bool], out: tuple[str | None, str | None, bool]
) -> bool:
    """Equal or coarser: dropping a month is a rephrasing, a different or added date is not."""
    for s, o in ((src[0], out[0]), (src[1], out[1])):
        if o and not (s and s.startswith(o)):
            return False
    return src[2] == out[2]


def _date_label(start: str | None, end: str | None, current: bool) -> str:
    return f"{start or '?'} \u2013 {'present' if current else end or '?'}"


def _check_experience(src: _Source, cand: ParsedResume, flags: _Flags) -> None:
    kept: set[int] = set()
    for i, job in enumerate(cand.experience):
        p = f"experience[{i}]"
        same_company = [
            (k, s)
            for k, s in enumerate(src.resume.experience)
            if normalize.same_name(s.company, job.company)
        ]
        if not same_company:
            flags.add(FlagCode.NEW_EMPLOYER, f"{p}.company", job.company)
        else:
            roles = [(k, s) for k, s in same_company if normalize.same_title(s.title, job.title)]
            if not roles:
                flags.add(FlagCode.NEW_JOB_TITLE, f"{p}.title", job.title)
            else:
                k, role = roles[0]
                kept.add(k)
                if not _dates_supported(
                    (role.start_date, role.end_date, role.is_current),
                    (job.start_date, job.end_date, job.is_current),
                ):
                    flags.add(
                        FlagCode.NEW_DATE_RANGE,
                        p,
                        _date_label(job.start_date, job.end_date, job.is_current),
                    )
                if (
                    job.location
                    and role.location
                    and not normalize.same_name(job.location, role.location)
                ) or (job.location and not role.location and not src.has_text(job.location)):
                    flags.add(FlagCode.CHANGED_FIELD, f"{p}.location", job.location)
        for j, bullet in enumerate(job.bullets):
            _check_text(bullet, f"{p}.bullets[{j}]", src, flags)
    for k, s in enumerate(src.resume.experience):
        if k not in kept and not any(
            normalize.same_name(s.company, j.company) for j in cand.experience
        ):
            flags.add(FlagCode.ENTRY_REMOVED, f"experience[{k}]", f"{s.title}, {s.company}")


def _check_education(src: _Source, cand: ParsedResume, flags: _Flags) -> None:
    kept: set[int] = set()
    for i, edu in enumerate(cand.education):
        p = f"education[{i}]"
        schools = [
            (k, s)
            for k, s in enumerate(src.resume.education)
            if normalize.same_name(s.institution, edu.institution)
        ]
        if not schools:
            flags.add(FlagCode.NEW_INSTITUTION, f"{p}.institution", edu.institution)
            continue
        out_degree = normalize.parse_degree(edu.degree, edu.field_of_study)
        if edu.degree or edu.field_of_study:
            matches = [
                (k, s)
                for k, s in schools
                if normalize.degree_supported(
                    out_degree, normalize.parse_degree(s.degree, s.field_of_study)
                )
            ]
        else:
            matches = schools
        if not matches:
            label = " ".join(x for x in (edu.degree, edu.field_of_study) if x)
            flags.add(FlagCode.NEW_DEGREE, f"{p}.degree", label)
            continue
        k, school = matches[0]
        kept.add(k)
        if not _dates_supported(
            (school.start_date, school.end_date, False), (edu.start_date, edu.end_date, False)
        ):
            flags.add(FlagCode.NEW_DATE_RANGE, p, _date_label(edu.start_date, edu.end_date, False))
    for k, s in enumerate(src.resume.education):
        if k not in kept and not any(
            normalize.same_name(s.institution, e.institution) for e in cand.education
        ):
            flags.add(FlagCode.ENTRY_REMOVED, f"education[{k}]", s.institution)


def _check_certifications(src: _Source, cand: ParsedResume, flags: _Flags) -> None:
    for i, cert in enumerate(cand.certifications):
        p = f"certifications[{i}]"
        known = [c for c in src.resume.certifications if normalize.same_name(c.name, cert.name)]
        if not known:
            flags.add(FlagCode.NEW_CERTIFICATION, f"{p}.name", cert.name)
            continue
        if cert.issuer and not any(
            c.issuer and normalize.same_name(c.issuer, cert.issuer) for c in known
        ):
            flags.add(FlagCode.NEW_CERTIFICATION, f"{p}.issuer", f"{cert.name}, {cert.issuer}")
        if cert.date and not any(c.date and c.date.startswith(cert.date) for c in known):
            flags.add(FlagCode.NEW_DATE_RANGE, f"{p}.date", cert.date)


def _check_projects(src: _Source, cand: ParsedResume, flags: _Flags) -> None:
    for i, project in enumerate(cand.projects):
        p = f"projects[{i}]"
        if not any(normalize.same_name(s.name, project.name) for s in src.resume.projects):
            flags.add(FlagCode.NEW_PROJECT, f"{p}.name", project.name)
        if project.url and normalize.norm_url(project.url) not in src.urls:
            flags.add(FlagCode.NEW_URL, f"{p}.url", project.url)
        if project.description:
            _check_text(project.description, f"{p}.description", src, flags)
        for j, tech in enumerate(project.technologies):
            if normalize.canonical_skill(tech) not in src.skill_keys and not src.has_text(tech):
                flags.add(FlagCode.NEW_SKILL, f"{p}.technologies[{j}]", tech)


def _check_skills(src: _Source, cand: ParsedResume, flags: _Flags) -> None:
    for i, skill in enumerate(cand.skills):
        key = normalize.canonical_skill(skill)
        if key not in src.skill_keys and not (len(key) >= 3 and key in src.compact_text):
            flags.add(FlagCode.NEW_SKILL, f"skills[{i}]", skill)


def _check_contact(src: _Source, cand: ParsedResume, flags: _Flags) -> None:
    out, base = cand.contact, src.resume.contact
    if out.full_name and not (
        base.full_name and normalize.same_name(out.full_name, base.full_name)
    ):
        flags.add(FlagCode.CONTACT_CHANGED, "contact.full_name", out.full_name)
    if out.location and not (base.location and normalize.same_name(out.location, base.location)):
        flags.add(FlagCode.CONTACT_CHANGED, "contact.location", out.location)
    if out.email and normalize.norm_email(out.email) not in src.emails:
        flags.add(FlagCode.NEW_EMAIL, "contact.email", out.email)
    if out.phone and normalize.norm_phone(out.phone) not in src.phones:
        flags.add(FlagCode.NEW_PHONE, "contact.phone", out.phone)
    for i, link in enumerate(out.links):
        if normalize.norm_url(link.url) not in src.urls:
            flags.add(FlagCode.NEW_URL, f"contact.links[{i}].url", link.url)


def _check_injection(
    cand: ParsedResume, src: _Source, job_description: str | None, flags: _Flags
) -> None:
    _check_injection_texts(_strings(cand), src, job_description, flags)


def _check_injection_texts(
    items: Iterable[tuple[str, str]], src: _Source, job_description: str | None, flags: _Flags
) -> None:
    for path, text in items:
        if injection.REDACTION in text:
            flags.add(FlagCode.INJECTION_LEAKAGE, path, text)
            continue
        for sentence in injection.injected_sentences(text):
            if not src.has_text(sentence):
                flags.add(FlagCode.INJECTION_LEAKAGE, path, sentence)
        if job_description:
            if injection.leaked_instruction_text(text, job_description) and not src.has_text(text):
                flags.add(FlagCode.INJECTION_LEAKAGE, path, text)
            elif injection.copied_job_text(text, job_description) and not _in_shingles(text, src):
                flags.add(FlagCode.JOB_TEXT_COPIED, path, text)


def _in_shingles(text: str, src: _Source) -> bool:
    tokens = normalize.words(text)
    return any(tuple(tokens[i : i + 8]) in src.shingles for i in range(len(tokens) - 7))


def _free_text(cand: ParsedResume) -> Iterable[tuple[str, str]]:
    if cand.headline:
        yield "headline", cand.headline
    if cand.summary:
        yield "summary", cand.summary


def check_resume(
    source: ParsedResume, candidate: ParsedResume, job_description: str | None = None
) -> FactCheckResult:
    """Every entity of `candidate` that `source` does not support, with a severity."""
    src = _Source.of(source)
    flags = _Flags()
    _check_contact(src, candidate, flags)
    for path, text in _free_text(candidate):
        _check_text(text, path, src, flags)
    _check_experience(src, candidate, flags)
    _check_education(src, candidate, flags)
    _check_certifications(src, candidate, flags)
    _check_projects(src, candidate, flags)
    _check_skills(src, candidate, flags)
    _check_injection(candidate, src, job_description, flags)
    blocking = sum(1 for f in flags.items if f.severity is Severity.BLOCKING)
    return FactCheckResult(
        passed=blocking == 0,
        blocking=blocking,
        warnings=len(flags.items) - blocking,
        flags=sorted(
            flags.items, key=lambda f: (f.severity is not Severity.BLOCKING, f.path or "")
        ),
    )


# --- generated prose: cover letters and screening answers -----------------------------

PLACEHOLDER_RE = re.compile(
    r"\[[^\]\n]{1,80}\]"
    r"|\{\{[^}\n]{0,80}\}\}"
    r"|<[A-Za-z][^<>\n]{0,60}>"
    r"|\bNEEDS_INPUT\b"
    r"|\b(?:your|company|hiring\s+manager|recipient)\s+name\b"
    r"|\binsert\s+\w+(?:\s+\w+)?\s+here\b"
    r"|\blorem\s+ipsum\b|\bx{3,}\b",
    re.IGNORECASE,
)
_NUMBER_WORD_ALT = "|".join(sorted(normalize.NUMBER_WORDS, key=len, reverse=True))
_YEARS_CLAIM = re.compile(
    rf"(?<![\w.])(\d{{1,2}}|{_NUMBER_WORD_ALT})\s*\+?\s*(?:-\s*)?(?:years?|yrs?)\b",
    re.IGNORECASE,
)


def _claimed_years(text: str) -> Iterable[tuple[str, int]]:
    for match in _YEARS_CLAIM.finditer(text):
        values = normalize.numbers_in(match.group(1))
        if values and values[0].isdigit():
            yield match.group(0), int(values[0])


def _month(value: str | None, *, end: bool) -> int | None:
    if not value or len(value) < 4 or not value[:4].isdigit():
        return None
    year = int(value[:4])
    if len(value) >= 7 and value[5:7].isdigit():
        return year * 12 + int(value[5:7]) - 1 + (1 if end else 0)
    return year * 12 + (12 if end else 0)


def _spans(resume: ParsedResume, as_of: date) -> list[tuple[int, int]]:
    now = as_of.year * 12 + as_of.month
    spans: list[tuple[int, int]] = []
    for job in resume.experience:
        start = _month(job.start_date, end=False)
        end = now if job.is_current or not job.end_date else _month(job.end_date, end=True)
        if start is not None and end is not None and end >= start:
            spans.append((start, end))
    return spans


def total_experience_months(resume: ParsedResume, as_of: date) -> int:
    """Months covered by the resume's roles (overlapping roles counted once)."""
    merged: list[list[int]] = []
    for start, end in sorted(_spans(resume, as_of)):
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return sum(end - start for start, end in merged)


def _experience_years(resume: ParsedResume, as_of: date) -> set[int]:
    """Whole-year values that honestly describe the resume's experience (each role, the total)."""
    allowed: set[int] = set()
    for start, end in _spans(resume, as_of):
        months = end - start
        allowed |= {months // 12, -(-months // 12)}
    total = total_experience_months(resume, as_of)
    allowed |= {total // 12, -(-total // 12)}
    return {y for y in allowed if y > 0}


def check_texts(
    source: ParsedResume,
    items: Sequence[tuple[str, str]],
    job_description: str | None = None,
    *,
    allowed_context: str = "",
    years_experience: int | None = None,
    as_of: date | None = None,
) -> FactCheckResult:
    """Fact check of generated prose (a cover letter, screening answers) against the resume.

    The same entity checks as `check_resume` run on each `(path, text)` (employers, schools,
    degrees, certifications, links, metrics, skills, injected instructions), plus two that only
    make sense for prose: a PLACEHOLDER (BLOCKING) and a claim of N years of experience that the
    resume's dates, the profile's `years_experience` or the user's own words do not support
    (NEW_EXPERIENCE_YEARS, BLOCKING). `allowed_context` is text the writer may use besides the
    resume: the job's title and company, and what the user typed. A company named in it is not an
    employer: "joined Acme" is still flagged unless the resume says so.
    """
    src = _Source.of(source, allowed_context)
    flags = _Flags()
    allowed_years = _experience_years(source, as_of or date.today())
    if years_experience is not None:
        allowed_years.add(years_experience)
    for _, value in _claimed_years(src.text):
        allowed_years.add(value)
    for path, text in items:
        for match in PLACEHOLDER_RE.finditer(text):
            flags.add(FlagCode.PLACEHOLDER, path, match.group(0))
        for written, value in _claimed_years(text):
            if value not in allowed_years:
                flags.add(FlagCode.NEW_EXPERIENCE_YEARS, path, written)
        # The claim itself was judged above; "some years" keeps the sentence readable for the cues.
        _check_text(_YEARS_CLAIM.sub("some years", text), path, src, flags)
    _check_injection_texts(items, src, job_description, flags)
    blocking = sum(1 for f in flags.items if f.severity is Severity.BLOCKING)
    return FactCheckResult(
        passed=blocking == 0,
        blocking=blocking,
        warnings=len(flags.items) - blocking,
        flags=sorted(
            flags.items, key=lambda f: (f.severity is not Severity.BLOCKING, f.path or "")
        ),
    )
