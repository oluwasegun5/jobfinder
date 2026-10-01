"""Normalisation shared by the fact check: names, titles, degrees, skills, numbers, URLs.

Everything here is deterministic and keyless. The tolerance is deliberately narrow: a name matches
after case, punctuation and legal-suffix folding (and a very high fuzzy ratio for typos); a skill
matches through an explicit alias table; a degree matches on its level and subject words. Anything
looser would let an invented entity through as "a rephrasing".
"""

import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher

_TOKEN = re.compile(r"[a-z0-9+#]+")
_LEGAL_SUFFIXES = frozenset(
    {
        "inc",
        "incorporated",
        "llc",
        "ltd",
        "limited",
        "corp",
        "corporation",
        "co",
        "company",
        "gmbh",
        "plc",
        "sa",
        "ag",
        "bv",
        "pty",
        "lp",
        "llp",
    }
)
_TITLE_WORDS = {
    "sr": "senior",
    "jr": "junior",
    "eng": "engineer",
    "engr": "engineer",
    "dev": "developer",
    "mgr": "manager",
}
_NAME_RATIO = 0.92
_TITLE_RATIO = 0.9

_NUMBER_WORDS = {
    "one": "1",
    "two": "2",
    "three": "3",
    "four": "4",
    "five": "5",
    "six": "6",
    "seven": "7",
    "eight": "8",
    "nine": "9",
    "ten": "10",
    "eleven": "11",
    "twelve": "12",
    "fifteen": "15",
    "twenty": "20",
    "thirty": "30",
    "fifty": "50",
    "hundred": "100",
}


def fold(text: str) -> str:
    """Case- and accent-folded text."""
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold()


def words(text: str) -> list[str]:
    """Lower-case word tokens (letters, digits, `+` and `#`, so `c++` and `c#` survive)."""
    return _TOKEN.findall(fold(text).replace("&", " and "))


def norm_name(text: str) -> str:
    """An organisation name folded for comparison: no case, punctuation or legal suffix."""
    tokens = [t for t in words(text) if t not in _LEGAL_SUFFIXES]
    return " ".join(tokens)


def same_name(a: str, b: str) -> bool:
    na, nb = norm_name(a), norm_name(b)
    if not na or not nb:
        return na == nb
    return na == nb or SequenceMatcher(None, na, nb).ratio() >= _NAME_RATIO


def norm_title(text: str) -> str:
    return " ".join(_TITLE_WORDS.get(token, token) for token in words(text))


def same_title(a: str, b: str) -> bool:
    na, nb = norm_title(a), norm_title(b)
    return na == nb or SequenceMatcher(None, na, nb).ratio() >= _TITLE_RATIO


# --- degrees -------------------------------------------------------------------------------

_DEGREE_STOP = frozenset(
    {
        "of",
        "in",
        "and",
        "the",
        "a",
        "an",
        "degree",
        "with",
        "honours",
        "honors",
        "hons",
        "first",
        "second",
        "upper",
        "lower",
        "class",
        "major",
        "minor",
        "for",
    }
)
# (pattern, level, extra subject token). The abbreviations imply their subject ("BSc" is a
# bachelor of science), so "BSc" and "Bachelor of Science" compare equal.
_DEGREE_FORMS: tuple[tuple[re.Pattern[str], str, str | None], ...] = tuple(
    (re.compile(pattern), level, subject)
    for pattern, level, subject in (
        (
            r"\bph\.?\s?d\b\.?|\bdoctorate\b|\bdoctoral\b|\bdoctor of philosophy\b|\bd\.?phil\b",
            "doctorate",
            None,
        ),
        (r"\bm\.?\s?b\.?\s?a\b\.?", "master", "business"),
        (r"\bm\.?\s?sc\b\.?|\bmaster'?s? of science\b", "master", "science"),
        (r"\bm\.?\s?eng\b\.?|\bmaster'?s? of engineering\b", "master", "engineering"),
        (r"\bm\.?\s?tech\b\.?|\bmaster'?s? of technology\b", "master", "technology"),
        (r"\bmaster'?s? of arts\b", "master", "arts"),
        (r"\bmaster'?s?\b", "master", None),
        (r"\bb\.?\s?sc\b\.?|\bbachelor'?s? of science\b", "bachelor", "science"),
        (r"\bb\.?\s?eng\b\.?|\bbachelor'?s? of engineering\b", "bachelor", "engineering"),
        (r"\bb\.?\s?tech\b\.?|\bbachelor'?s? of technology\b", "bachelor", "technology"),
        (r"\bbachelor'?s? of arts\b", "bachelor", "arts"),
        (r"\bbachelor'?s?\b", "bachelor", None),
        (r"\bassociate'?s?\b", "associate", None),
        (r"\bdiploma\b", "diploma", None),
    )
)
# Abbreviations that are only unambiguous when the whole degree string is the abbreviation.
_BARE_DEGREES = {
    "bs": ("bachelor", "science"),
    "ba": ("bachelor", "arts"),
    "ms": ("master", "science"),
    "ma": ("master", "arts"),
}


@dataclass(frozen=True, slots=True)
class DegreeIdentity:
    level: str | None
    subject: frozenset[str]


def degree_levels_in(text: str) -> set[str]:
    """Degree levels a free-text string claims (`PhD`, `MBA`, `Bachelor of ...`)."""
    folded = fold(text)
    return {level for pattern, level, _ in _DEGREE_FORMS if pattern.search(folded)}


def parse_degree(degree: str | None, field: str | None) -> DegreeIdentity:
    """Level and subject words of a degree entry; `degree` and `field` are read together."""
    text = fold(" ".join(p for p in (degree, field) if p))
    levels: list[str] = []
    subject: set[str] = set()
    remaining = text
    for pattern, level, extra in _DEGREE_FORMS:
        if pattern.search(remaining):
            levels.append(level)
            if extra:
                subject.add(extra)
            remaining = pattern.sub(" ", remaining)
    if not levels and degree:
        bare = " ".join(words(degree))
        if bare in _BARE_DEGREES:
            level, extra = _BARE_DEGREES[bare]
            levels.append(level)
            subject.add(extra)
            remaining = field or ""
    subject |= {w for w in words(remaining) if w not in _DEGREE_STOP}
    return DegreeIdentity(levels[0] if levels else None, frozenset(subject))


def degree_supported(out: DegreeIdentity, source: DegreeIdentity) -> bool:
    """The output's degree says nothing the source's does not: same level, no new subject word."""
    return out.level == source.level and out.subject <= source.subject


# --- skills ----------------------------------------------------------------------------------

# Compact forms (letters and digits, `+` and `#`) mapped to one canonical compact form.
_SKILL_ALIASES = {
    "js": "javascript",
    "ecmascript": "javascript",
    "ts": "typescript",
    "k8s": "kubernetes",
    "k8": "kubernetes",
    "postgres": "postgresql",
    "psql": "postgresql",
    "pgsql": "postgresql",
    "golang": "go",
    "nodejs": "node",
    "reactjs": "react",
    "vuejs": "vue",
    "nextjs": "next",
    "expressjs": "express",
    "amazonwebservices": "aws",
    "googlecloudplatform": "gcp",
    "googlecloud": "gcp",
    "microsoftazure": "azure",
    "ml": "machinelearning",
    "mongo": "mongodb",
    "springboot": "spring",
    "springframework": "spring",
    "dotnet": "net",
    "csharp": "c#",
    "cpp": "c++",
    "restful": "rest",
    "restapi": "rest",
    "restapis": "rest",
    "restfulapi": "rest",
    "restfulapis": "rest",
    "sqlserver": "mssql",
    "microsoftsqlserver": "mssql",
    "continuousintegration": "cicd",
    "cicdpipelines": "cicd",
    "oop": "objectorientedprogramming",
    "gha": "githubactions",
    "microservice": "microservices",
    "unittests": "unittesting",
}


def compact(text: str) -> str:
    return "".join(_TOKEN.findall(fold(text)))


def canonical_skill(text: str) -> str:
    """One comparable key per skill: `Node.js`, `NodeJS` and `node js` are the same key."""
    key = compact(text)
    return _SKILL_ALIASES.get(key, key)


# --- numbers, dates, links -----------------------------------------------------------------------

_YEAR = re.compile(r"\b(?:19[5-9]\d|20\d\d)\b")
_NUMBER = re.compile(r"(?<![\w.])\d[\d,]*(?:\.\d+)?(?![\w.]*\d)")


def numbers_in(text: str) -> list[str]:
    """Numeric values written in the text (digits, plus common number words), normalised."""
    folded = fold(text)
    found = [m.group(0).replace(",", "") for m in _NUMBER.finditer(folded)]
    for token in _TOKEN.findall(folded):
        if token in _NUMBER_WORDS:
            found.append(_NUMBER_WORDS[token])
    return [n.rstrip(".") for n in found]


def years_in(text: str) -> set[str]:
    return set(_YEAR.findall(text))


def strip_years(text: str) -> str:
    return _YEAR.sub(" ", text)


_SCHEME = re.compile(r"^(?:https?://)?(?:www\.)?", re.IGNORECASE)


def norm_url(url: str) -> str:
    return _SCHEME.sub("", url.strip().casefold()).rstrip("/.,);:")


def norm_email(email: str) -> str:
    return email.strip().casefold()


def norm_phone(phone: str) -> str:
    return re.sub(r"\D", "", phone)
