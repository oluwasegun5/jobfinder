"""The keyless stand-in's mock interview feedback and summary (`LLM_PROVIDER=fake`).

Deterministic heuristics over the data blocks a real model would read
(docs/adr/0034-mock-interview.md): length, connectives, overlap with the question, numbers and
named things for the scores, and keyword cues for Situation, Task, Action and Result. Every
strength quotes a sentence of the answer exactly, so the grounding check keeps it, and nothing is
invented. It carries no understanding: it exists to exercise the pipeline and the UI offline. The
same answer gets the same scores whatever the persona.
"""

import re
from typing import Any

from app.factcheck.injection import REDACTION
from app.factcheck.normalize import words

_SENTENCE = re.compile(r"[^.!?\n]+[.!?]?")
_CONNECTIVES = re.compile(
    r"\b(first|firstly|second|then|next|finally|because|so that|therefore|as a result|"
    r"for example)\b",
    re.IGNORECASE,
)
_SITUATION = re.compile(
    r"\b(when|while|during|at my|in my previous|last year|on a project|our team|we had)\b",
    re.IGNORECASE,
)
_TASK = re.compile(
    r"\b(my (role|task|goal|job|responsibility)|i was (responsible|asked|tasked)|"
    r"i needed to|i had to|the goal was|needed to)\b",
    re.IGNORECASE,
)
_ACTION = re.compile(
    r"\b(i (built|wrote|led|designed|implemented|created|decided|added|fixed|ran|organi[sz]ed|"
    r"set up|proposed|introduced|refactored|migrated|measured|talked|asked|worked|reviewed))\b",
    re.IGNORECASE,
)
_RESULT = re.compile(
    r"\b(result|resulted|as a result|reduced|improved|increased|saved|delivered|shipped|cut|"
    r"fewer|faster|lesson)\b|\d",
    re.IGNORECASE,
)
_NAMED = re.compile(r"[A-Z][A-Za-z0-9+#]+")
_STOPWORDS = frozenset(
    (  # noqa: SIM905 - a word list reads better as prose
        "the a an and or of to in on for with your you do does did how what why when is are "
        "was were tell me about describe would could should have has had it its that this "
        "these those"
    ).split()
)

_OPENERS: dict[str, list[tuple[str, str]]] = {
    "conversational": [
        ("behavioral", "To start, tell me about yourself and what draws you to the {title} role."),
        ("role_specific", "What would you want to achieve in your first three months as {title}?"),
        ("behavioral", "Tell me about a piece of work you are proud of and why it mattered."),
        ("role_specific", "What kind of team and ways of working bring out your best work?"),
    ],
    "behavioral_probing": [
        ("behavioral", "Tell me about a time you disagreed with a colleague. What did you do?"),
        ("behavioral", "Describe a time a project you owned went off track. How did you recover?"),
        ("behavioral", "Give me an example of when you had to learn something quickly at work."),
        ("behavioral", "Tell me about a time you gave difficult feedback to someone."),
    ],
    "technical_depth": [
        (
            "technical",
            "Walk me through how you would debug a problem that only appears in production.",
        ),
        (
            "technical",
            "How would you design a reliable process for releasing changes to {title} work?",
        ),
        (
            "technical",
            "What trade-offs do you weigh when choosing between a quick fix and a lasting one?",
        ),
        (
            "technical",
            "Explain a technical concept from your work to someone who is not technical.",
        ),
    ],
    "scenario_based": [
        (
            "role_specific",
            "Suppose a key deadline moves up by a week. How would you re-plan the work?",
        ),
        (
            "role_specific",
            "Imagine two stakeholders want opposite things from you. What do you do first?",
        ),
        (
            "role_specific",
            "Suppose you inherit unfinished work with no documentation. How do you begin?",
        ),
        (
            "role_specific",
            "Imagine your first big task as {title} is unclear. How do you get clarity?",
        ),
    ],
}


def _content_words(text: str) -> set[str]:
    return {w for w in words(text) if len(w) > 2 and w not in _STOPWORDS}


def _sentences(text: str) -> list[str]:
    return [m.group(0).strip() for m in _SENTENCE.finditer(text) if m.group(0).strip()]


def _next_question(data: dict[str, Any]) -> dict[str, str]:
    from app.interview.mock_grounding import is_repeat

    title = data["job"].get("title") or "this"
    style = data["persona"].get("question_style", "conversational")
    asked: list[str] = data["asked"]
    bank = _OPENERS.get(style, _OPENERS["conversational"])
    skills = [s for s in data["job"].get("skills", []) if isinstance(s, str) and s]
    candidates = [(c, q.format(title=title)) for c, q in bank]
    candidates += [
        ("technical", f"Tell me about the most demanding way you have used {skill}.")
        for skill in skills[:4]
    ]
    candidates += [(c, q.format(title=title)) for items in _OPENERS.values() for c, q in items]
    for category, question in candidates:
        if not is_repeat(question, asked):
            return {"category": category, "question": question}
    return {
        "category": "role_specific",
        "question": f"Is there anything about the {title} role you would like to ask me about?",
    }


def _clamp(n: int) -> int:
    return max(1, min(5, n))


def turn(user_message: str) -> dict[str, Any]:
    from app.interview.mock_message import parse_turn_message

    data = parse_turn_message(user_message)
    out: dict[str, Any] = {}
    mode = data["mode"]
    if data["answer"] is not None:
        answer: str = data["answer"]
        question: str = data["question"]["question"]
        behavioral = data["question"]["category"] == "behavioral"
        text = answer.replace(REDACTION, " ")
        count = len(text.split())
        sentences = _sentences(text)

        structure = _clamp(
            1
            + (count >= 20)
            + (count >= 45)
            + bool(_CONNECTIVES.search(text))
            + (len(sentences) >= 3)
        )
        wanted = _content_words(question)
        overlap = len(wanted & _content_words(text)) / len(wanted) if wanted else 0.0
        relevance = _clamp(1 + round(overlap * 6) + (count >= 25))
        details = len(re.findall(r"\d+", text)) + sum(
            len(_NAMED.findall(" ".join(sentence.split()[1:]))) for sentence in sentences
        )
        specificity = _clamp(1 + min(3, details) + (count >= 40))
        flags = {
            "situation": bool(_SITUATION.search(text)),
            "task": bool(_TASK.search(text)),
            "action": bool(_ACTION.search(text)),
            "result": bool(_RESULT.search(text)),
        }
        star: dict[str, Any]
        if behavioral:
            star = {"score": _clamp(1 + sum(flags.values())), **flags}
        else:
            star = {"score": None, "situation": None, "task": None, "action": None, "result": None}
        dims = [structure, relevance, specificity] + ([star["score"]] if behavioral else [])
        overall = _clamp(round(sum(dims) / len(dims)))

        strengths: list[dict[str, str]] = []
        best = max(sentences, key=lambda s: (len(re.findall(r"\d+", s)), len(s)), default="")
        if best and len(best.split()) >= 4 and relevance >= 3:
            strengths.append(
                {
                    "text": "You answered the question with a point you can build on.",
                    "quote": best[:200],
                }
            )
        if specificity >= 3 and sentences:
            detail = next((s for s in sentences if re.search(r"\d", s)), sentences[0])
            strengths.append(
                {"text": "You backed your answer with a concrete detail.", "quote": detail[:200]}
            )
        improvements: list[dict[str, Any]] = []
        if specificity <= 3:
            improvements.append(
                {
                    "text": "Add a concrete example with a real detail, such as a number.",
                    "quote": None,
                }
            )
        if behavioral and not flags["result"]:
            improvements.append({"text": "Finish with the result of what you did.", "quote": None})
        if structure <= 3:
            improvements.append(
                {
                    "text": "Open with a one-sentence answer, then explain in a clear order.",
                    "quote": None,
                }
            )
        if not improvements:
            improvements.append({"text": "Keep each answer to about two minutes.", "quote": None})
        out["feedback"] = {
            "structure": structure,
            "relevance": relevance,
            "specificity": specificity,
            "star": star,
            "overall": overall,
            "strengths": strengths,
            "improvements": improvements,
        }
    if mode in ("feedback_and_question", "question_only"):
        out["next_question"] = _next_question(data)
    return out


def summary(user_message: str) -> dict[str, Any]:
    from app.interview.mock_message import parse_summary_message

    data = parse_summary_message(user_message)
    averages = data["averages"]
    turns = data["turns"]
    weakest = min(
        (k for k in ("structure", "relevance", "specificity") if averages.get(k) is not None),
        key=lambda k: averages[k],
    )
    narrative = (
        f"You answered {len(turns)} question{'s' if len(turns) != 1 else ''} with an average "
        f"overall score of {averages['overall']} out of 5. Your weakest area was {weakest}."
    )
    steps = {
        "structure": "Practise opening each answer with a one-sentence summary of your point.",
        "relevance": "Reread the question before you answer and tie each part back to it.",
        "specificity": "Prepare two examples from your own work with a real detail and an outcome.",
    }
    ordered = sorted(steps, key=lambda k: averages.get(k) or 0)
    next_steps = [steps[k] for k in ordered]
    next_steps.append("Rehearse your answers aloud and keep each to about two minutes.")
    return {"narrative": narrative, "next_steps": next_steps[:3]}
