"""The keyless stand-in's cover letters and screening answers (`LLM_PROVIDER=fake`).

Templates over the resume's own facts (docs/adr/0031-cover-letters-and-application-pack.md): every
sentence is built from the candidate's most recent role, the skills they list that the job also
mentions, and their own bullets word for word. Nothing is invented, so the fact check passes. Tone
and length change the output deterministically (the wording, and how many parts there are), which
is what lets the options be tested without a model. The job text and the notes are read only for
keyword overlap and the job's title and company, never echoed.
"""

import re
from typing import Any

from app.factcheck.injection import REDACTION
from app.parsing.schema import Experience, ParsedResume
from app.writing.common import Length, Tone

_WORD = re.compile(r"[a-z0-9+#.]+")


def _words(text: str) -> set[str]:
    return {w for w in _WORD.findall(text.casefold()) if len(w) > 1}


def _join(items: list[str]) -> str:
    if len(items) <= 2:
        return " and ".join(items)
    return ", ".join(items[:-1]) + " and " + items[-1]


def _ref(value: str | None) -> str | None:
    """A job title or company that is usable in a sentence (not a redaction marker)."""
    return None if not value or REDACTION in value else value


def _lower_first(text: str) -> str:
    first = text.split(" ", 1)[0]
    if len(first) > 1 and any(c.isupper() for c in first[1:]):
        return text
    return text[:1].lower() + text[1:]


def _sentence(text: str) -> str:
    text = text.strip()
    return text if text.endswith((".", "!", "?")) else text + "."


def _ranked_skills(resume: ParsedResume, job: dict[str, str], limit: int) -> list[str]:
    wanted = _words(f"{job.get('title', '')} {job.get('description', '')}")
    ranked = sorted(resume.skills, key=lambda s: -len(_words(s) & wanted))
    return ranked[:limit]


def _ranked_bullets(role: Experience | None, job: dict[str, str]) -> list[str]:
    if role is None:
        return []
    wanted = _words(f"{job.get('title', '')} {job.get('description', '')}")
    return sorted(role.bullets, key=lambda b: -len(_words(b) & wanted))


def cover_letter(user_message: str) -> dict[str, Any]:
    # Imported here: `app.writing` imports `app.llm`, which imports this module.
    from app.writing.letter import CLOSING_DEFAULT
    from app.writing.letter import parse_message as parse_letter_message

    resume, job, _notes, tone, length = parse_letter_message(user_message)
    title = _ref(job.get("title")) or "open"
    company = _ref(job.get("company"))
    at_company = f" at {company}" if company else ""
    role = resume.experience[0] if resume.experience else None
    skills = _ranked_skills(resume, job, 3)
    bullets = _ranked_bullets(role, job)
    background = (
        f"{role.title} at {role.company}"
        if role
        else (f"work with {_join(skills)}" if skills else "my background")
    )

    opening = {
        Tone.FORMAL: f"I am writing to apply for the {title} position{at_company}. "
        f"My background as {background} has prepared me well for this role.",
        Tone.WARM: f"I was delighted to see the {title} opening{at_company} and I would love to be "
        f"considered. My time as {background} is a big part of why.",
        Tone.CONCISE: f"I am applying for the {title} role{at_company}. My background: {background}.",  # noqa: E501
    }[tone]
    skills_paragraph = (
        {
            Tone.FORMAL: f"My experience includes {_join(skills)}, which matches what this role "
            "calls for.",
            Tone.WARM: f"I enjoy working with {_join(skills)}, and I think that fits well with what "  # noqa: E501
            "your team needs.",
            Tone.CONCISE: f"Relevant skills: {_join(skills)}.",
        }[tone]
        if skills
        else None
    )
    achievement = None
    if role and bullets:
        achievement = {
            Tone.FORMAL: f"My work at {role.company} produced a result I am glad to highlight. "
            f"{_sentence(bullets[0])}",
            Tone.WARM: f"A result I am proud of from {role.company}: {_sentence(bullets[0])}",
            Tone.CONCISE: f"At {role.company}: {_sentence(bullets[0])}",
        }[tone]
    second = f"I also {_sentence(_lower_first(bullets[1]))}" if role and len(bullets) > 1 else None
    close = {
        Tone.FORMAL: "I would welcome the opportunity to discuss how I can contribute to your team. "  # noqa: E501
        "Thank you for your consideration.",
        Tone.WARM: "I would love to chat about how I could help. Thank you so much for your time.",
        Tone.CONCISE: "I would welcome a conversation. Thank you.",
    }[tone]

    paragraphs: list[str | None]
    if length is Length.SHORT:
        paragraphs = [opening, " ".join(x for x in (achievement, close) if x)]
    elif length is Length.STANDARD:
        paragraphs = [opening, skills_paragraph, achievement, close]
    else:
        paragraphs = [opening, skills_paragraph, achievement, second, resume.summary, close]
    salutation = {
        Tone.FORMAL: "Dear Hiring Manager,",
        Tone.WARM: f"Hello {company} team," if company else "Hello hiring team,",
        Tone.CONCISE: "Dear Hiring Team,",
    }[tone]
    return {
        "salutation": salutation,
        "paragraphs": [p for p in paragraphs if p],
        "closing": CLOSING_DEFAULT[tone],
    }


def screening(user_message: str) -> dict[str, Any]:
    from app.writing.screening import parse_message as parse_screening_message

    resume, job, context, _notes, tone, length = parse_screening_message(user_message)
    title = _ref(job.get("title")) or "open"
    company = _ref(job.get("company"))
    at_company = f" at {company}" if company else ""
    role = resume.experience[0] if resume.experience else None
    matching = context.get("matching_skills", [])[:3] or _ranked_skills(resume, job, 3)
    gaps = context.get("gaps", [])
    bullets = _ranked_bullets(role, job)
    background = f"{role.title} at {role.company}" if role else "my background"
    long = length is Length.LONG
    short = length is Length.SHORT

    clause = f", especially with {_join(matching)}" if matching and not short else ""
    why = {
        Tone.FORMAL: f"I am interested in the {title} role{at_company} because it builds on my "
        f"work as {background}{clause}.",
        Tone.WARM: f"The {title} role{at_company} really appeals to me because it builds on my "
        f"work as {background}{clause}.",
        Tone.CONCISE: f"The {title} role{at_company} fits my background as {background}.",
    }[tone]
    if long and matching:
        why += f" I would bring hands-on experience with {_join(matching)} to the team."

    top = _ranked_skills(resume, job, 3)
    strengths = (
        {
            Tone.FORMAL: f"My main strengths are {_join(top)}, which I have applied as {background}.",  # noqa: E501
            Tone.WARM: f"I am strongest at {_join(top)}, and I have used them day to day as "
            f"{background}.",
            Tone.CONCISE: f"Strengths: {_join(top)}.",
        }[tone]
        if top
        else f"My main strength is the experience I gained as {background}."
    )

    if gaps:
        growth = {
            Tone.FORMAL: f"I would like to grow my depth in {gaps[0]}, which this role involves and "  # noqa: E501
            "my CV does not yet show.",
            Tone.WARM: f"I would love to grow in {gaps[0]}, which this role involves and my CV does "  # noqa: E501
            "not yet show.",
            Tone.CONCISE: f"Growth area: {gaps[0]}, which my CV does not yet show.",
        }[tone]
    else:
        target = top[0] if top else "my craft"
        growth = f"I want to keep deepening my skills in {target}."

    if role and bullets:
        achievement = {
            Tone.FORMAL: f"At {role.company}, my most significant achievement was this: "
            f"{_sentence(bullets[0])}",
            Tone.WARM: f"I am proudest of this from {role.company}: {_sentence(bullets[0])}",
            Tone.CONCISE: f"At {role.company}: {_sentence(bullets[0])}",
        }[tone]
    else:
        achievement = f"My most significant achievement is the work I did as {background}."

    return {
        "answers": [
            {"id": "WHY_COMPANY_ROLE", "answer": why},
            {"id": "STRENGTHS", "answer": strengths},
            {"id": "GROWTH_AREA", "answer": growth},
            {"id": "BIGGEST_ACHIEVEMENT", "answer": achievement},
        ]
    }
