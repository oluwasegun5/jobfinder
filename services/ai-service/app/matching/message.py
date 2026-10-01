"""The user message of a score-matches call: the candidate and each job as delimited data blocks.

Every block's markers carry a random token the untrusted text cannot know, and any marker-lookalike
inside the text is defused, so a posting cannot close its own block and pose as instructions. The
only trusted instruction sits after the blocks. `parse_message` is the inverse, used by the keyless
heuristic provider (and tests).
"""

import json
import re
from uuid import UUID

from app.matching.schema import Candidate, JobPosting

_CANDIDATE_MARKER = "<<<CANDIDATE_"
_JOB_MARKER = "<<<JOB_"

_CANDIDATE_BLOCK = re.compile(
    r"<<<CANDIDATE_BEGIN (\w+)>>>\n(.*?)\n<<<CANDIDATE_END \1>>>", re.DOTALL
)
_JOB_BLOCK = re.compile(
    r"<<<JOB_BEGIN (\w+) ([0-9a-f-]{36})>>>\n(.*?)\n<<<JOB_END \1 \2>>>", re.DOTALL
)


def _defuse(text: str) -> str:
    return text.replace(_CANDIDATE_MARKER, "<<< CANDIDATE_").replace(_JOB_MARKER, "<<< JOB_")


def _truncate(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    cut = text[:limit]
    space = cut.rfind(" ")
    return (cut[:space] if space > limit - 200 else cut).rstrip()


def candidate_payload(candidate: Candidate) -> str:
    return _defuse(
        json.dumps(candidate.model_dump(mode="json", exclude_none=True), ensure_ascii=False)
    )


def job_payload(job: JobPosting, description_chars: int) -> str:
    data = job.model_dump(mode="json", exclude_none=True)
    if "description" in data:
        data["description"] = _truncate(data["description"], description_chars)
    return _defuse(json.dumps(data, ensure_ascii=False))


def job_block(job: JobPosting, nonce: str, description_chars: int) -> str:
    payload = job_payload(job, description_chars)
    return f"<<<JOB_BEGIN {nonce} {job.id}>>>\n{payload}\n<<<JOB_END {nonce} {job.id}>>>"


def build_user_message(
    candidate: Candidate, jobs: list[JobPosting], nonce: str, description_chars: int
) -> str:
    blocks = [
        f"<<<CANDIDATE_BEGIN {nonce}>>>\n{candidate_payload(candidate)}\n"
        f"<<<CANDIDATE_END {nonce}>>>",
        *(job_block(job, nonce, description_chars) for job in jobs),
    ]
    ids = ", ".join(str(job.id) for job in jobs)
    return (
        "\n\n".join(blocks)
        + "\n\nScore each job above for the candidate above and reply with the JSON object "
        f"described in your instructions. The job ids, in order, are: {ids}."
    )


def parse_message(message: str) -> tuple[Candidate, list[JobPosting]]:
    """Reads the blocks back out of a user message built by `build_user_message`."""
    found = _CANDIDATE_BLOCK.search(message)
    if found is None:
        raise ValueError("no candidate block in the message")
    candidate = Candidate.model_validate_json(found.group(2))
    jobs = [
        JobPosting.model_validate_json(match.group(3))
        for match in _JOB_BLOCK.finditer(message)
        if UUID(match.group(2))
    ]
    return candidate, jobs
