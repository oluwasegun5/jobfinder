"""The keyless stand-in's interview questions and company brief (`LLM_PROVIDER=fake`).

Templates over the data blocks a real model would read (docs/adr/0033-interview-prep.md). The
questions are generic prompts filled with the job's own skills and title; every brief claim
restates one field and quotes it exactly, so the grounding check keeps all of them and nothing is
invented. It carries no understanding: it exists to exercise the pipeline offline.
"""

import re
from typing import Any

from app.factcheck.injection import REDACTION

_SENTENCE = re.compile(r"[^.!?\n]+[.!?]?")

_BEHAVIORAL = (
    ("Tell me about a time you disagreed with a teammate. How did you resolve it?", "medium"),
    ("Describe a project that went wrong and what you learned from it.", "medium"),
    ("How do you prioritise when several tasks are urgent at once?", "easy"),
    ("Tell me about a time you took ownership of something outside your job description.", "hard"),
)


def _usable(value: object) -> str | None:
    return value if isinstance(value, str) and value and REDACTION not in value else None


def questions(user_message: str) -> dict[str, Any]:
    from app.interview.message import parse_questions_message

    resume, job, count = parse_questions_message(user_message)
    title = _usable(job.get("title")) or "this role"
    company = _usable(job.get("company"))
    at_company = f" at {company}" if company else ""
    skills = [s for s in job.get("skills", []) if _usable(s)]
    have = {s.casefold() for s in resume.skills}
    # Skills the candidate also lists come first: those are the ones they can be asked to defend.
    skills.sort(key=lambda s: s.casefold() not in have)
    seniority = _usable(job.get("seniority"))

    items: list[dict[str, str]] = []
    for text, difficulty in _BEHAVIORAL:
        items.append(
            {
                "category": "behavioral",
                "question": text,
                "rationale": f"Behavioural questions are common for a {title} interview.",
                "difficulty": difficulty,
            }
        )
    for i, skill in enumerate(skills[:6]):
        listed = skill.casefold() in have
        items.append(
            {
                "category": "technical",
                "question": f"How have you used {skill}, and what would you do differently now?"
                if listed
                else f"How would you get productive with {skill} in your first weeks?",
                "rationale": f"The job asks for {skill}"
                + (", which your resume also lists." if listed else "."),
                "difficulty": ("easy", "medium", "hard")[i % 3],
            }
        )
    if not skills:
        items.append(
            {
                "category": "technical",
                "question": f"Walk me through a technical problem you solved that fits {title}.",
                "rationale": "The job names no skills, so a general technical question is likely.",
                "difficulty": "medium",
            }
        )
    role = [
        (
            f"Why are you interested in the {title} role{at_company}?",
            f"Interviewers ask why you want the {title} role.",
            "easy",
        ),
        (
            f"What would your first 90 days look like as {title}?",
            f"The job is {seniority} level, so expect questions about ownership."
            if seniority
            else "Interviewers ask how a new hire would start.",
            "medium",
        ),
        (
            f"What does good look like in the {title} job after a year?",
            "Interviewers probe how you would measure success in the role.",
            "hard",
        ),
    ]
    for text, rationale, difficulty in role:
        items.append(
            {
                "category": "role_specific",
                "question": text,
                "rationale": rationale,
                "difficulty": difficulty,
            }
        )
    # Interleave the categories so a cut to `count` keeps all three.
    by_cat = {
        c: [i for i in items if i["category"] == c]
        for c in ("behavioral", "technical", "role_specific")
    }
    ordered: list[dict[str, str]] = []
    while any(by_cat.values()) and len(ordered) < max(count, 6):
        for cat in by_cat:
            if by_cat[cat]:
                ordered.append(by_cat[cat].pop(0))
    return {"questions": ordered[: max(count, 6)]}


def _first_sentence(text: str) -> str | None:
    for match in _SENTENCE.finditer(text):
        sentence = match.group(0).strip()
        if len(sentence.split()) >= 4 and REDACTION not in sentence:
            return sentence[:280].rstrip()
    return None


def brief(user_message: str) -> dict[str, Any]:
    from app.interview.message import parse_brief_message

    fields = parse_brief_message(user_message)

    def claim(statement: str, source: str, evidence: str) -> dict[str, str]:
        return {"statement": statement, "source": source, "evidence": evidence}

    sections: list[dict[str, Any]] = []

    role: list[dict[str, str]] = []
    if title := fields.get("job.title"):
        role.append(claim(f"The role is {title}.", "job.title", title))
    if (description := fields.get("job.description")) and (
        sentence := _first_sentence(description)
    ):
        role.append(
            claim(f"The posting says: {sentence}", "job.description", sentence.rstrip(".!?"))
        )
    if role:
        sections.append({"id": "ROLE_OVERVIEW", "claims": role})

    company: list[dict[str, str]] = []
    if name := fields.get("company.name"):
        company.append(claim(f"The employer is {name}.", "company.name", name))
    if industry := fields.get("company.industry"):
        company.append(
            claim(f"The company's industry is {industry}.", "company.industry", industry)
        )
    if size := fields.get("company.size"):
        company.append(claim(f"The company's size is {size}.", "company.size", size))
    if domain := fields.get("company.domain"):
        company.append(claim(f"The company's website is {domain}.", "company.domain", domain))
    if company:
        sections.append({"id": "COMPANY_FACTS", "claims": company})

    if skills := fields.get("job.skills"):
        quoted = ", ".join(skills.split(", ")[:5])
        sections.append(
            {
                "id": "SKILLS_AND_TOOLS",
                "claims": [claim(f"The posting lists {quoted}.", "job.skills", quoted)],
            }
        )

    logistics: list[dict[str, str]] = []
    for key, label in (
        ("job.location", "The location is"),
        ("job.work_mode", "The work mode is"),
        ("job.employment_type", "The employment type is"),
        ("job.seniority", "The seniority is"),
        ("job.salary", "The salary is"),
    ):
        if value := fields.get(key):
            logistics.append(claim(f"{label} {value}.", key, value))
    if logistics:
        sections.append({"id": "LOGISTICS_AND_PAY", "claims": logistics})

    return {"sections": sections, "unknowns": []}
