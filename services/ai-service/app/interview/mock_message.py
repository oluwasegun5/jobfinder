"""The data blocks of the mock interview prompts (docs/adr/0034-mock-interview.md).

Everything a model reads that came from outside is in a delimited block with a random per-request
token (the scheme of the other writing features): the job, the question being answered, the
candidate's answer, the questions asked so far and, for the summary, the stored feedback. The
persona is chosen by code from closed vocabularies and is sent as plain JSON, not as a block. The
candidate's resume is never sent: feedback is about the answer, and a model that has not seen the
resume cannot credit the candidate with anything in it.
"""

import json
from typing import Any

from app.interview.mock_schema import Persona, SummaryTurn
from app.writing.common import block, block_re, defuse, job_json


def persona_json(persona: Persona) -> str:
    return json.dumps(persona.model_dump(mode="json"))


def turn_message(
    persona: Persona,
    *,
    title: str,
    company: str | None,
    description: str,
    skills: list[str],
    question: str | None,
    category: str | None,
    answer: str | None,
    asked: list[str],
    mode: str,
    nonce: str,
) -> str:
    """`description` and `answer` went through `scrub_job_text`. `mode` is `feedback_only`,
    `feedback_and_question` or `question_only`."""
    parts = [f"PERSONA: {persona_json(persona)}"]
    extra: dict[str, object] = {"skills": skills} if skills else {}
    parts.append(block("JOB", nonce, job_json(title, company, description, **extra)))
    if question is not None and answer is not None:
        parts.append(
            block(
                "QUESTION", nonce, defuse(json.dumps({"category": category, "question": question}))
            )
        )
        parts.append(block("ANSWER", nonce, defuse(answer)))
    parts.append(block("ASKED", nonce, defuse(json.dumps(asked, ensure_ascii=False))))
    parts.append(
        "Do what your instructions describe for this interview turn. "
        f"Options: {json.dumps({'mode': mode})}"
    )
    return "\n\n".join(parts)


def parse_turn_message(message: str) -> dict[str, Any]:
    """Reads the blocks back out of a message built by `turn_message` (fake provider)."""
    job = block_re("JOB").search(message)
    asked = block_re("ASKED").search(message)
    if job is None or asked is None:
        raise ValueError("no job or asked block in the message")
    question = block_re("QUESTION").search(message)
    answer = block_re("ANSWER").search(message)
    persona = message.split("PERSONA: ", 1)[1].split("\n", 1)[0]
    return {
        "persona": json.loads(persona),
        "job": json.loads(job.group(2)),
        "question": json.loads(question.group(2)) if question else None,
        "answer": answer.group(2) if answer else None,
        "asked": json.loads(asked.group(2)),
        "mode": json.loads(message.rsplit("Options: ", 1)[1])["mode"],
    }


def summary_message(
    persona: Persona,
    *,
    title: str,
    turns: list[SummaryTurn],
    averages: dict[str, float | None],
    top_strengths: list[str],
    top_improvements: list[str],
    ended_early: bool,
    nonce: str,
) -> str:
    """The stored feedback of the session (not the candidate's answers) and the averages that code
    computed. The model writes only the narrative and the next steps."""
    data = {
        "job_title": title,
        "ended_early": ended_early,
        "averages": averages,
        "top_strengths": top_strengths,
        "top_improvements": top_improvements,
        "turns": [
            {
                "question": t.question,
                "category": t.category.value,
                "scores": {
                    "structure": t.feedback.structure,
                    "relevance": t.feedback.relevance,
                    "specificity": t.feedback.specificity,
                    "star_completeness": t.feedback.star.score,
                    "overall": t.feedback.overall,
                },
                "strengths": [p.text for p in t.feedback.strengths],
                "improvements": [p.text for p in t.feedback.improvements],
            }
            for t in turns
        ],
    }
    return (
        f"PERSONA: {persona_json(persona)}\n\n"
        f"{block('SESSION', nonce, defuse(json.dumps(data, ensure_ascii=False)))}\n\n"
        "Write the narrative and the next steps described in your instructions."
    )


def parse_summary_message(message: str) -> dict[str, Any]:
    session = block_re("SESSION").search(message)
    if session is None:
        raise ValueError("no session block in the message")
    data: dict[str, Any] = json.loads(session.group(2))
    return data
