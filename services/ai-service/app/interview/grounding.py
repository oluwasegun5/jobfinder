"""The grounding check of the company brief (docs/adr/0033-interview-prep.md), in the spirit of
`app.factcheck`: deterministic, keyless, and strict where a claim could mislead.

A brief claim is `statement` + `source` (the name of a job or company field) + `evidence` (a quote).
It is kept only if all of this holds, otherwise it is dropped and the reason is reported:

* the source field exists and has text (`EMPTY_SOURCE`);
* the evidence is a contiguous run of words of that field (`EVIDENCE_NOT_FOUND`): case, punctuation
  and spacing are ignored, nothing else is;
* every number of the statement is in the source field (`UNSUPPORTED_NUMBER`) and every proper
  name in it (a capitalised word that does not start the sentence) is in the source field, the
  company's name or the job's title (`UNSUPPORTED_NAME`);
* at least `MIN_SUPPORT` of the statement's content words occur in the source field
  (`UNSUPPORTED_STATEMENT`), so "well funded" cannot ride on a quote that says "Acme";
* the claim does not repeat a sentence of the posting that was an instruction to an AI
  (`INSTRUCTION_LEAK`);
* it is not a repeat of a claim already kept (`DUPLICATE`).

The fields the check reads are the ones the model was shown: the posting after instruction-like
sentences were removed, so a "fact" that only an injected sentence stated has no evidence to find.
"""

import re
from collections.abc import Mapping
from dataclasses import dataclass, field

from app.factcheck.injection import REDACTION, is_instruction_like, leaked_instruction_text
from app.factcheck.normalize import words
from app.interview.schema import (
    DroppedClaim,
    DropReason,
    LlmClaim,
    LlmSection,
    SectionId,
    SourceField,
)

# Share of the statement's content words that must be found in the source field.
MIN_SUPPORT = 0.6

_NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
_NAME = re.compile(r"\b[A-Z][A-Za-z0-9+#]*(?:[-'][A-Za-z0-9+#]+)*")
_STOP = frozenset(
    (  # noqa: SIM905 - a word list reads better as prose
        "a an and are as at be been being but by can for from has have in into is it "
        "its of on or our that the their them then there these they this to was we were "
        "will with you your also any all more most other such than very who what when "
        "where which while would should could may might must not no yes per via within "
        "across about over under up out "
        # Verbs that only link a statement to its source ("works in", "is based in", "lists").
        "work works working operate operates operating base based locate located list "
        "lists listed state states stated say says said include includes offer offers "
        "describe describes mention mentions name named call called"
    ).split()
)
# Words that describe what a field is, so "The company has 201-500 people" can cite company.size.
_DESCRIPTORS: dict[SourceField, str] = {
    SourceField.JOB_TITLE: "title role position job",
    SourceField.JOB_DESCRIPTION: "posting role position job responsibility duty involve team",
    SourceField.JOB_LOCATION: "location office place city country",
    SourceField.JOB_WORK_MODE: "work mode remote onsite office arrangement",
    SourceField.JOB_EMPLOYMENT_TYPE: "employment type contract hours",
    SourceField.JOB_SENIORITY: "seniority level grade",
    SourceField.JOB_SALARY: "salary pay paid compensation range",
    SourceField.JOB_SKILLS: "skill technology tool requirement requirements",
    SourceField.COMPANY_NAME: "company employer organisation organization",
    SourceField.COMPANY_DOMAIN: "company website site web domain address",
    SourceField.COMPANY_SIZE: "company size employee employees people staff headcount",
    SourceField.COMPANY_INDUSTRY: "company industry sector business market",
}


def stem(word: str) -> str:
    """A crude suffix strip, enough to match "builds" with "build" and "services" with "service"."""
    for suffix in ("ing", "ed", "es", "s", "e", "ly"):
        if word.endswith(suffix) and len(word) - len(suffix) >= 3:
            return word[: -len(suffix)]
    return word


def _content_stems(text: str) -> list[str]:
    return [stem(w) for w in words(text) if len(w) >= 3 and w not in _STOP and not w.isdigit()]


def _substantive(tokens: list[str]) -> bool:
    """Does a quote have a word or a number that is not a function word (so it is evidence)?"""
    return any(t not in _STOP and (len(t) >= 3 or t.isdigit()) for t in tokens)


def _contains_run(haystack: list[str], needle: list[str]) -> bool:
    n = len(needle)
    if n == 0 or n > len(haystack):
        return False
    return any(haystack[i : i + n] == needle for i in range(len(haystack) - n + 1))


def _labels(source: SourceField) -> set[str]:
    return {stem(w) for w in _DESCRIPTORS[source].split()}


def _unsupported_specific(
    text: str, number_tokens: set[str], name_tokens: set[str]
) -> DropReason | None:
    """A number that is not in `number_tokens`, or a proper name that is not in `name_tokens`."""
    for number in _NUMBER.findall(text):
        if any(token not in number_tokens for token in words(number)):
            return DropReason.UNSUPPORTED_NUMBER
    for match in _NAME.finditer(text):
        if match.start() == 0:
            continue  # the first word of a sentence is capitalised anyway
        if any(token not in name_tokens for token in words(match.group(0))):
            return DropReason.UNSUPPORTED_NAME
    return None


def says_nothing_new(text: str, fields: Mapping[SourceField, str], context: str = "") -> bool:
    """For the brief's `unknowns`: text that introduces no number or name the fields lack.

    An unknown is a statement of what is missing; it must not be a way to state a fact the model
    made up ("The company was founded in 1850, which we could not confirm").
    """
    tokens = set(words(context))
    for value in fields.values():
        tokens |= set(words(value))
    return _unsupported_specific(text, tokens, tokens) is None


@dataclass(frozen=True, slots=True)
class GroundingResult:
    kept: list[tuple[SectionId, LlmClaim]] = field(default_factory=list)
    dropped: list[DroppedClaim] = field(default_factory=list)


def _check(
    claim: LlmClaim,
    fields: Mapping[SourceField, str],
    context_tokens: set[str],
    raw_description: str,
) -> DropReason | None:
    text = fields.get(claim.source, "")
    field_tokens = words(text)
    if not field_tokens:
        return DropReason.EMPTY_SOURCE
    if REDACTION in claim.statement or REDACTION in claim.evidence:
        return DropReason.INSTRUCTION_LEAK
    both = f"{claim.statement} {claim.evidence}"
    if is_instruction_like(both) or (
        raw_description and leaked_instruction_text(both, raw_description)
    ):
        return DropReason.INSTRUCTION_LEAK
    evidence_tokens = words(claim.evidence)
    if not _substantive(evidence_tokens) or not _contains_run(field_tokens, evidence_tokens):
        return DropReason.EVIDENCE_NOT_FOUND

    supported = set(field_tokens) | context_tokens
    specifics = _unsupported_specific(claim.statement, set(field_tokens), supported)
    if specifics is not None:
        return specifics

    stems = _content_stems(claim.statement)
    known = {stem(w) for w in supported} | _labels(claim.source)
    if stems and sum(1 for s in stems if s in known) / len(stems) < MIN_SUPPORT:
        return DropReason.UNSUPPORTED_STATEMENT
    return None


def ground_claims(
    sections: list[LlmSection],
    fields: Mapping[SourceField, str],
    *,
    raw_description: str = "",
    context: str = "",
) -> GroundingResult:
    """Keeps the claims the fields support, in order, and reports why each other claim was dropped.

    `fields` are the texts the model was shown, `raw_description` is the posting as received (only
    used to recognise copied instruction sentences) and `context` is text a statement may name
    without citing it: the company's name and the job's title.
    """
    context_tokens = set(words(context))
    kept: list[tuple[SectionId, LlmClaim]] = []
    dropped: list[DroppedClaim] = []
    seen: set[tuple[str, ...]] = set()
    for section in sections:
        for claim in section.claims:
            reason = _check(claim, fields, context_tokens, raw_description)
            key = tuple(words(claim.statement))
            if reason is None and key in seen:
                reason = DropReason.DUPLICATE
            if reason is not None:
                dropped.append(DroppedClaim(source=claim.source, reason=reason))
                continue
            seen.add(key)
            kept.append((section.id, claim))
    return GroundingResult(kept=kept, dropped=dropped)
