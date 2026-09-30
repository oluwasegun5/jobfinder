"""Strict schema for a parsed CV (PLAN.md §5 `resume_versions.structured`, §7 CV parsing).

Design rules, because the model's output is untrusted input:

* The schema contains only facts a CV states. There is deliberately no score, confidence,
  "verified" or any other field in which the model describes itself or its own output, and
  `extra="forbid"` means one cannot be smuggled in.
* Every string is stripped of control characters and length-bounded; every list is bounded.
* Links must be plain http(s) URLs, so a hostile CV cannot plant a `javascript:` link that
  the web app later renders.
"""

import re
from typing import Annotated, Literal, Self

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, field_validator, model_validator

_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f-\x9f]")
_DATE = re.compile(r"^\d{4}(-(0[1-9]|1[0-2]))?$")
_URL = re.compile(r"^https?://\S+$", re.IGNORECASE)
_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def _clean(value: object) -> object:
    if isinstance(value, str):
        return _CONTROL_CHARS.sub(" ", value).strip()
    return value


def _clean_or_none(value: object) -> object:
    cleaned = _clean(value)
    # Models often answer "" for "not stated".
    return None if cleaned == "" else cleaned


Short = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=200)]
Long = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=2000)]
Skill = Annotated[str, BeforeValidator(_clean), Field(min_length=1, max_length=100)]
OptShort = Annotated[Annotated[str, Field(max_length=200)] | None, BeforeValidator(_clean_or_none)]
OptLong = Annotated[Annotated[str, Field(max_length=2000)] | None, BeforeValidator(_clean_or_none)]
# Partial ISO dates: "2021" or "2021-06". Nothing else is stored.
PartialDate = Annotated[
    Annotated[str, Field(pattern=_DATE.pattern)] | None, BeforeValidator(_clean_or_none)
]
Url = Annotated[str, BeforeValidator(_clean), Field(max_length=500, pattern=_URL.pattern)]
OptUrl = Annotated[
    Annotated[str, Field(max_length=500, pattern=_URL.pattern)] | None,
    BeforeValidator(_clean_or_none),
]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


def _check_period(start: str | None, end: str | None) -> None:
    # Zero-padded partial ISO dates of equal precision sort lexicographically; mixed precision
    # ("2020" vs "2020-06") still compares correctly on the shared prefix.
    if start and end and end[: len(start)] < start[: len(end)]:
        raise ValueError("end_date is before start_date")


class Link(_Strict):
    label: OptShort = None
    url: Url


class Contact(_Strict):
    full_name: OptShort = None
    email: OptShort = None
    phone: OptShort = None
    location: OptShort = None
    links: list[Link] = Field(default_factory=list, max_length=10)

    @field_validator("email")
    @classmethod
    def _email_shape(cls, value: str | None) -> str | None:
        if value is not None and not _EMAIL.match(value):
            raise ValueError("not an email address")
        return value


class Experience(_Strict):
    company: Short
    title: Short
    location: OptShort = None
    start_date: PartialDate = None
    end_date: PartialDate = None
    is_current: bool = False
    bullets: list[Long] = Field(default_factory=list, max_length=25)

    @model_validator(mode="after")
    def _consistent_dates(self) -> Self:
        if self.is_current and self.end_date is not None:
            raise ValueError("a current role has no end_date")
        _check_period(self.start_date, self.end_date)
        return self


class Education(_Strict):
    institution: Short
    degree: OptShort = None
    field_of_study: OptShort = None
    start_date: PartialDate = None
    end_date: PartialDate = None

    @model_validator(mode="after")
    def _consistent_dates(self) -> Self:
        _check_period(self.start_date, self.end_date)
        return self


class Project(_Strict):
    name: Short
    description: OptLong = None
    url: OptUrl = None
    technologies: list[Short] = Field(default_factory=list, max_length=25)


class Certification(_Strict):
    name: Short
    issuer: OptShort = None
    date: PartialDate = None


class ParsedResume(_Strict):
    # Set by code, never by the model: lets readers of the stored JSON detect the shape.
    schema_version: Literal[1] = 1
    contact: Contact = Field(default_factory=Contact)
    headline: OptShort = None
    summary: OptLong = None
    experience: list[Experience] = Field(default_factory=list, max_length=30)
    education: list[Education] = Field(default_factory=list, max_length=15)
    skills: list[Skill] = Field(default_factory=list, max_length=100)
    projects: list[Project] = Field(default_factory=list, max_length=20)
    certifications: list[Certification] = Field(default_factory=list, max_length=20)

    @field_validator("skills")
    @classmethod
    def _dedupe_skills(cls, value: list[str]) -> list[str]:
        seen: set[str] = set()
        unique: list[str] = []
        for skill in value:
            key = skill.casefold()
            if key not in seen:
                seen.add(key)
                unique.append(skill)
        return unique
