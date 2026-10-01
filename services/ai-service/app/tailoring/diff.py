"""From a model's tailored resume to the result core-api stores: guardrails in code, then a diff.

The model's output is never taken as is. `finalize` first puts back what the model may not touch
(the contact block, and with `rewrite_summary=false` the headline and summary), restores the
source order of entries (the model may reorder bullets and skills, not jobs or schools), and
applies the bullet limit. `diff` then computes the changes itself, by comparing the finished
resume with the source: the model's own account of what it changed is only used for the
rationale text, so the before/after shown to the user can never misdescribe what changed.
"""

import re
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel

from app.factcheck import normalize
from app.parsing.schema import ParsedResume
from app.tailoring.schema import (
    Change,
    ChangeNote,
    ChangeOp,
    LlmTailorOutput,
    Section,
    TailorOptions,
)

_UNIT = re.compile(
    r"^(experience|education|projects|certifications)\[(\d+)\]|^(headline|summary|skills)"
)
_ENTRY_SECTIONS: tuple[tuple[Section, str], ...] = (
    (Section.EXPERIENCE, "experience"),
    (Section.EDUCATION, "education"),
    (Section.PROJECTS, "projects"),
    (Section.CERTIFICATIONS, "certifications"),
)


@dataclass(frozen=True, slots=True)
class _Pair:
    """One tailored entry: its index in the source (None if new) and in the model's output."""

    source_index: int | None
    model_index: int


def _same(section: str) -> Callable[[Any, Any], bool]:
    def experience(a: Any, b: Any) -> bool:
        return normalize.same_name(a.company, b.company) and normalize.same_title(a.title, b.title)

    def education(a: Any, b: Any) -> bool:
        return normalize.same_name(a.institution, b.institution)

    def named(a: Any, b: Any) -> bool:
        return normalize.same_name(a.name, b.name)

    return {
        "experience": experience,
        "education": education,
        "projects": named,
        "certifications": named,
    }[section]


def _pair_entries(section: str, source: Sequence[Any], out: Sequence[Any]) -> list[_Pair]:
    """Match tailored entries to source entries: by identity, then (leftovers) by position."""
    same = _same(section)
    taken: set[int] = set()
    matched: list[int | None] = []
    for entry in out:
        found = next((k for k, s in enumerate(source) if k not in taken and same(s, entry)), None)
        if found is not None:
            taken.add(found)
        matched.append(found)
    if section == "experience":
        # Same employer, different title: still the same entry (the title change is then flagged).
        for i, entry in enumerate(out):
            if matched[i] is None:
                found = next(
                    (
                        k
                        for k, s in enumerate(source)
                        if k not in taken and normalize.same_name(s.company, entry.company)
                    ),
                    None,
                )
                if found is not None:
                    taken.add(found)
                    matched[i] = found
    # What is left on both sides replaces one another in order ("renamed employer" is one change).
    free_source = [k for k in range(len(source)) if k not in taken]
    for i in range(len(out)):
        if matched[i] is None and free_source:
            matched[i] = free_source.pop(0)
    return [_Pair(m, i) for i, m in enumerate(matched)]


def _in_source_order(pairs: list[_Pair]) -> list[_Pair]:
    """Entries that exist in the source go back to the source's order; new ones keep their slot."""
    slots = [i for i, p in enumerate(pairs) if p.source_index is not None]
    ordered = sorted((pairs[i] for i in slots), key=lambda p: p.source_index or 0)
    result = list(pairs)
    for slot, pair in zip(slots, ordered, strict=True):
        result[slot] = pair
    return result


@dataclass(frozen=True, slots=True)
class Finished:
    resume: ParsedResume
    # section name -> pairs in the finished resume's order
    pairs: dict[str, list[_Pair]]


def finalize(source: ParsedResume, output: LlmTailorOutput, options: TailorOptions) -> Finished:
    """The model's resume with the guardrails applied that code can enforce."""
    resume = output.resume
    update: dict[str, Any] = {"contact": source.contact}
    if not options.rewrite_summary:
        update["headline"] = source.headline
        update["summary"] = source.summary
    pairs: dict[str, list[_Pair]] = {}
    for _, name in _ENTRY_SECTIONS:
        entries = list(getattr(resume, name))
        ordered = _in_source_order(_pair_entries(name, getattr(source, name), entries))
        pairs[name] = ordered
        rebuilt = [entries[p.model_index] for p in ordered]
        if name == "experience" and options.max_bullets_per_role is not None:
            limit = options.max_bullets_per_role
            rebuilt = [job.model_copy(update={"bullets": job.bullets[:limit]}) for job in rebuilt]
        update[name] = rebuilt
    return Finished(resume.model_copy(update=update), pairs)


def _dump(value: BaseModel) -> dict[str, Any]:
    return value.model_dump(mode="json")


def _notes_by_unit(notes: list[ChangeNote]) -> dict[str, str]:
    by_unit: dict[str, str] = {}
    for note in notes:
        match = _UNIT.match(note.path.strip())
        if match:
            unit = match.group(0)
            by_unit.setdefault(unit, note.rationale)
    return by_unit


def diff(source: ParsedResume, finished: Finished, notes: list[ChangeNote]) -> list[Change]:
    """The units that differ between the source and the finished resume, in resume order."""
    result = finished.resume
    rationale = _notes_by_unit(notes)
    changes: list[Change] = []

    def add(
        section: Section,
        op: ChangeOp,
        path: str,
        before: Any,
        after: Any,
        note_key: str,
        default: str,
    ) -> None:
        changes.append(
            Change(
                id=f"c{len(changes) + 1}",
                section=section,
                op=op,
                path=path,
                before=before,
                after=after,
                rationale=rationale.get(note_key, default),
            )
        )

    for section, field_name in (
        (Section.HEADLINE, "headline"),
        (Section.SUMMARY, "summary"),
    ):
        before, after = getattr(source, field_name), getattr(result, field_name)
        if before != after:
            add(
                section,
                ChangeOp.REPLACE,
                field_name,
                before,
                after,
                field_name,
                "Reworded for this job.",
            )
    for section, name in _ENTRY_SECTIONS:
        source_list, out_list = getattr(source, name), getattr(result, name)
        seen: set[int] = set()
        for position, pair in enumerate(finished.pairs[name]):
            entry = out_list[position]
            if pair.source_index is None:
                add(
                    section,
                    ChangeOp.ADD,
                    f"{name}[{position}]",
                    None,
                    _dump(entry),
                    f"{name}[{pair.model_index}]",
                    "Added by the model.",
                )
                continue
            seen.add(pair.source_index)
            before = _dump(source_list[pair.source_index])
            if before != _dump(entry):
                add(
                    section,
                    ChangeOp.REPLACE,
                    f"{name}[{pair.source_index}]",
                    before,
                    _dump(entry),
                    f"{name}[{pair.model_index}]",
                    "Reordered or reworded to match the job.",
                )
        for k, original in enumerate(source_list):
            if k not in seen:
                add(
                    section,
                    ChangeOp.REMOVE,
                    f"{name}[{k}]",
                    _dump(original),
                    None,
                    f"{name}[{k}]",
                    "Left out as less relevant to this job.",
                )
    if list(source.skills) != list(result.skills):
        add(
            Section.SKILLS,
            ChangeOp.REPLACE,
            "skills",
            list(source.skills),
            list(result.skills),
            "skills",
            "Skills reordered to put the most relevant first.",
        )
    return changes
