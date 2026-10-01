"""The user message of a tailor-resume call: the resume and the job as delimited data blocks.

Both blocks' markers carry a random token the untrusted text cannot know, any marker lookalike in
the text is defused, and the only trusted instruction sits after the blocks. The resume is sent
without its contact block: the model has no use for it (code copies it back from the source) and it
is personal data. `parse_message` is the inverse, used by the keyless heuristic provider.
"""

import json
import re

from app.factcheck.injection import REDACTION, is_instruction_like
from app.parsing.schema import ParsedResume
from app.tailoring.schema import TailorJob, TailorOptions

_RESUME_BLOCK = re.compile(r"<<<RESUME_BEGIN (\w+)>>>\n(.*?)\n<<<RESUME_END \1>>>", re.DOTALL)
_JOB_BLOCK = re.compile(r"<<<JOB_BEGIN (\w+)>>>\n(.*?)\n<<<JOB_END \1>>>", re.DOTALL)
_OPTIONS = re.compile(r"Options: (\{.*\})")


def _defuse(text: str) -> str:
    return text.replace("<<<", "< < <").replace(">>>", "> > >")


def resume_payload(resume: ParsedResume) -> str:
    data = resume.model_dump(mode="json", exclude={"contact"})
    return _defuse(json.dumps(data, ensure_ascii=False))


def job_payload(job: TailorJob, description: str) -> str:
    """`description` went through `scrub_job_text` already; title and company are checked here."""
    data: dict[str, str] = {
        "title": REDACTION if is_instruction_like(job.title) else job.title,
        "description": description,
    }
    if job.company:
        data["company"] = REDACTION if is_instruction_like(job.company) else job.company
    return _defuse(json.dumps(data, ensure_ascii=False))


def build_user_message(
    resume: ParsedResume, job: TailorJob, description: str, options: TailorOptions, nonce: str
) -> str:
    return (
        f"<<<RESUME_BEGIN {nonce}>>>\n{resume_payload(resume)}\n<<<RESUME_END {nonce}>>>\n\n"
        f"<<<JOB_BEGIN {nonce}>>>\n{job_payload(job, description)}\n<<<JOB_END {nonce}>>>\n\n"
        "Tailor the resume above to the job above and reply with the JSON object described in "
        "your instructions, using only the resume's facts. "
        f"Options: {json.dumps(options.model_dump(mode='json'))}"
    )


def parse_message(message: str) -> tuple[ParsedResume, dict[str, str], TailorOptions]:
    """Reads the blocks back out of a message built by `build_user_message`."""
    resume = _RESUME_BLOCK.search(message)
    job = _JOB_BLOCK.search(message)
    if resume is None or job is None:
        raise ValueError("no resume or job block in the message")
    options = _OPTIONS.search(message)
    return (
        ParsedResume.model_validate_json(resume.group(2)),
        json.loads(job.group(2)),
        TailorOptions.model_validate_json(options.group(1)) if options else TailorOptions(),
    )
