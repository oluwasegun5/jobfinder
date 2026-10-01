"""Fixtures for cover letters and screening answers: requests and what a model replies.

Everyone and everything is invented (the candidate and employers of `tests.fixtures.tailoring`).
`faithful_*` is what a well-behaved model returns; the `bad_*` helpers build what a misbehaving one
returns. They are scripted into the in-memory FakeProvider: no real model is involved, and the
production fake provider has no switch that produces them.
"""

import json
from typing import Any

from tests.fixtures import tailoring as fx

AS_OF = "2026-10-01"

LONG_SENTENCE = (
    "I led a team of 4 engineers delivering a billing platform in Java and Spring Boot "
    "and I reduced API p95 latency by 35% across 12 services at Northwind Systems."
)


def letter_request(**extra: Any) -> dict[str, Any]:
    return {
        "user_id": fx.USER_ID,
        "resume": fx.SOURCE,
        "job": fx.JOB,
        "as_of": AS_OF,
        **extra,
    }


def screening_request(**extra: Any) -> dict[str, Any]:
    return {
        "user_id": fx.USER_ID,
        "resume": fx.SOURCE,
        "job": {**fx.JOB, "skills": ["Java", "Kafka", "Kubernetes"]},
        "as_of": AS_OF,
        **extra,
    }


def faithful_letter() -> dict[str, Any]:
    return {
        "salutation": "Dear Hiring Manager,",
        "paragraphs": [
            "I am writing to apply for the Backend Engineer role at Harbor Freight Tech. "
            "My work as Senior Backend Engineer at Northwind Systems has prepared me well.",
            "At Northwind Systems I led a team of 4 engineers delivering a billing platform in "
            "Java and Spring Boot, and I migrated nightly batch jobs to Kafka consumers.",
            "I would welcome the chance to talk. Thank you for your consideration.",
        ],
        "closing": "Yours sincerely,",
    }


def letter_reply(letter: dict[str, Any] | None = None) -> str:
    return json.dumps(letter or faithful_letter())


def with_paragraph(text: str, index: int = 1) -> dict[str, Any]:
    letter = faithful_letter()
    letter["paragraphs"][index] = text
    return letter


def faithful_answers() -> dict[str, Any]:
    return {
        "answers": [
            {
                "id": "WHY_COMPANY_ROLE",
                "answer": "The Backend Engineer role at Harbor Freight Tech builds on my work "
                "with Java, Spring Boot and Kafka at Northwind Systems.",
            },
            {
                "id": "STRENGTHS",
                "answer": "My strengths are Java, Spring Boot and Kafka, applied as Senior "
                "Backend Engineer at Northwind Systems.",
            },
            {
                "id": "GROWTH_AREA",
                "answer": "I would like to grow in Kubernetes, which my CV does not show yet.",
            },
            {
                "id": "BIGGEST_ACHIEVEMENT",
                "answer": "At Northwind Systems I led a team of 4 engineers delivering a "
                "billing platform in Java and Spring Boot.",
            },
        ]
    }


def answers_reply(answers: dict[str, Any] | None = None) -> str:
    return json.dumps(answers or faithful_answers())


def with_answer(answer_id: str, text: str) -> dict[str, Any]:
    data = faithful_answers()
    for a in data["answers"]:
        if a["id"] == answer_id:
            a["answer"] = text
    return data
