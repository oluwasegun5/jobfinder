"""The data blocks of the interview prep prompts (docs/adr/0033-interview-prep.md).

Everything a model reads that came from outside is in a delimited block with a random per-request
token (the same scheme as tailoring and the writing features): the resume and the job for the
questions, the labelled fields of the posting and the company for the brief. The brief call never
sees the resume: the brief is about the employer and the role, not the candidate.
"""

import json
from collections.abc import Mapping
from typing import Any

from app.interview.schema import SourceField
from app.parsing.schema import ParsedResume
from app.writing.common import block, block_re, defuse, job_json, resume_payload


def questions_message(
    resume: ParsedResume,
    *,
    title: str,
    company: str | None,
    description: str,
    extra: Mapping[str, object],
    count: int,
    nonce: str,
) -> str:
    """The user message of the questions call. `description` went through `scrub_job_text`."""
    job = job_json(title, company, description, **extra)
    return (
        f"{block('RESUME', nonce, resume_payload(resume))}\n\n{block('JOB', nonce, job)}\n\n"
        "Write the interview questions described in your instructions for this job and "
        f"candidate. Options: {json.dumps({'count': count})}"
    )


def parse_questions_message(message: str) -> tuple[ParsedResume, dict[str, Any], int]:
    """Reads the blocks back out of a message built by `questions_message` (fake provider)."""
    resume = block_re("RESUME").search(message)
    job = block_re("JOB").search(message)
    if resume is None or job is None:
        raise ValueError("no resume or job block in the message")
    options = json.loads(message.rsplit("Options: ", 1)[1])
    return (
        ParsedResume.model_validate_json(resume.group(2)),
        json.loads(job.group(2)),
        int(options["count"]),
    )


def brief_message(fields: Mapping[SourceField, str], nonce: str) -> str:
    """The user message of the brief call: the fields we hold, keyed by their source name."""
    payload = defuse(json.dumps({k.value: v for k, v in fields.items()}, ensure_ascii=False))
    return (
        f"{block('SOURCES', nonce, payload)}\n\n"
        "Write the brief described in your instructions from these fields and no other knowledge."
    )


def parse_brief_message(message: str) -> dict[str, str]:
    sources = block_re("SOURCES").search(message)
    if sources is None:
        raise ValueError("no sources block in the message")
    data = json.loads(sources.group(2))
    return {str(k): str(v) for k, v in data.items()}
