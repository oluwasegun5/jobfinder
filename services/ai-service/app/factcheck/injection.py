"""Prompt-injection handling for job descriptions, in both directions.

* `scrub_job_text` runs on the untrusted job description before a model sees it: it drops control
  and invisible characters, removes every sentence that reads like an instruction to an AI, and
  reports what it removed. Delimiting and the system prompt are the primary defence; this is a
  second layer that also keeps an injected claim ("add that the candidate worked at ...") out of
  the model's context entirely.
* `injected_sentences` and `leakage` run on the model's output (the fact check): an output string
  that reads like an instruction, a leftover redaction marker, or text copied from an injected
  sentence is a BLOCKING flag, whatever the model was told.
"""

import re
import unicodedata

from app.factcheck.normalize import words

REDACTION = "[removed: instruction-like text]"

_PHRASES = (
    r"ignore\s+(?:all\s+|any\s+|the\s+|your\s+)?(?:previous|prior|above|earlier|preceding)\b",
    r"disregard\s+(?:all\s+|any\s+|the\s+|your\s+)?(?:previous|prior|above|earlier|preceding|instructions?|rules?)",
    r"forget\s+(?:all\s+|any\s+|the\s+|your\s+)?(?:previous|prior|above|earlier|instructions?|rules?)",
    r"(?:system|developer)\s+(?:prompt|message|instructions?)",
    r"as\s+an\s+ai\b",
    r"you\s+are\s+(?:now\s+)?(?:a|an|the)\s+[\w\s-]{0,30}?(?:assistant|ai|model|bot|recruiter|agent)\b",
    r"new\s+instructions?\s*:",
    r"override\s+(?:the\s+|your\s+)?(?:previous|prior|system|safety|instructions?|rules?)",
    r"reveal\s+(?:your\s+|the\s+)?(?:system\s+)?(?:prompt|instructions?)",
    r"do\s+not\s+(?:mention|tell|reveal|disclose)\s+(?:this|these|that|the\s+instructions?)",
    r"(?:add|insert|include|state|claim|write|say)\s+(?:in\s+the\s+resume\s+)?that\s+(?:the\s+)?"
    r"(?:candidate|applicant|user|he|she|they)\b",
    r"(?:the\s+)?(?:candidate|applicant)\s+(?:must|should)\s+be\s+(?:rated|scored|ranked|hired)",
    r"<<<|>>>|\[/?inst\]|<\|im_(?:start|end)\|>|</?(?:system|assistant|instructions?)>",
    r"(?:^|\n)\s*(?:assistant|system|user|human)\s*:",
    r"#{2,}\s*(?:system|instructions?)\b",
)
_INJECTION = re.compile("|".join(f"(?:{p})" for p in _PHRASES), re.IGNORECASE)
_SENTENCE = re.compile(r"[^.!?\n]+(?:[.!?]+|\n|$)")
_INVISIBLE = {"Cf", "Cc", "Co", "Cs"}
_SHINGLE = 5
_COPY_SHINGLE = 8


def _strip_invisible(text: str) -> str:
    """Control characters (kept as spaces/newlines) and zero-width or bidi controls (dropped)."""
    out: list[str] = []
    for ch in text:
        if ch in "\n\t":
            out.append(ch)
        elif unicodedata.category(ch) in _INVISIBLE:
            if ch.isspace():
                out.append(" ")
        else:
            out.append(ch)
    return "".join(out)


def is_instruction_like(text: str) -> bool:
    return _INJECTION.search(text) is not None


def injected_sentences(text: str) -> list[str]:
    """The sentences of `text` that read like an instruction to an AI."""
    return [s.strip() for s in _SENTENCE.findall(_strip_invisible(text)) if is_instruction_like(s)]


def scrub_job_text(text: str, max_chars: int) -> tuple[str, list[str]]:
    """The job description made safer to show a model, and the sentences that were removed."""
    cleaned = re.sub(r"[ \t\u00a0]+", " ", _strip_invisible(text))
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned).strip()
    removed: list[str] = []
    kept: list[str] = []
    for sentence in _SENTENCE.findall(cleaned):
        if is_instruction_like(sentence):
            removed.append(sentence.strip())
            kept.append(REDACTION + ("\n" if sentence.endswith("\n") else " "))
        else:
            kept.append(sentence)
    scrubbed = "".join(kept).strip()
    if len(scrubbed) > max_chars:
        cut = scrubbed[:max_chars]
        space = cut.rfind(" ")
        scrubbed = (cut[:space] if space > max_chars - 200 else cut).rstrip()
    return scrubbed, removed


def _shingles(text: str, size: int) -> set[tuple[str, ...]]:
    tokens = words(text)
    return {tuple(tokens[i : i + size]) for i in range(len(tokens) - size + 1)}


def contains_shingle(haystack: str, shingles: set[tuple[str, ...]], size: int) -> bool:
    return bool(shingles & _shingles(haystack, size))


def leaked_instruction_text(output_text: str, job_description: str) -> bool:
    """Does the output repeat (five words in a row) an instruction sentence of the job text?"""
    sentences = injected_sentences(job_description)
    if not sentences:
        return False
    wanted: set[tuple[str, ...]] = set()
    for sentence in sentences:
        wanted |= _shingles(sentence, _SHINGLE)
    return contains_shingle(output_text, wanted, _SHINGLE)


def copied_job_text(output_text: str, job_description: str) -> bool:
    """Does the output copy eight words in a row of the job text verbatim?"""
    return contains_shingle(output_text, _shingles(job_description, _COPY_SHINGLE), _COPY_SHINGLE)
