"""Deterministic checks on what a model wrote about a mock interview answer
(docs/adr/0034-mock-interview.md), in the spirit of `grounding.py` and `app.factcheck`.

* A **strength** says something about what the candidate said, so it must carry a quote that is a
  verbatim substring of the answer (case, spacing and invisible characters ignored) and may name no
  number or proper name that the answer does not contain. Otherwise it is dropped.
* An **improvement** is advice. It may have a quote, and then the quote must be in the answer; its
  text may name only numbers and names found in the answer, the question or the job title/skills.
* A point that repeats an instruction-like sentence of the answer, or reads like an instruction to
  an AI, is dropped; so is a repeat of a point already kept.
* Scores are never trusted blindly: with no grounded strength nothing scores above 3, a very short
  answer cannot score above 2 on structure, specificity or STAR, and the overall score stays
  within one point of the mean of the dimensions.

None of this needs a model. Dropped points are counted, never returned: they may carry injected
text.
"""

import re
import unicodedata
from collections.abc import Iterable, Mapping, Sequence
from statistics import fmean

from app.factcheck.injection import REDACTION, is_instruction_like, leaked_instruction_text
from app.factcheck.normalize import NUMBER_WORDS, fold, words
from app.interview.mock_schema import (
    Averages,
    Feedback,
    LlmImprovement,
    LlmStar,
    LlmStrength,
    Point,
    PrepQuestion,
    Star,
    SummaryTurn,
)
from app.interview.schema import Category

SHORT_ANSWER_WORDS = 15
CAPPED_SCORE = 3
SHORT_CAP = 2

_NAME = re.compile(r"[A-Z][A-Za-z0-9+#]*(?:[-'][A-Za-z0-9+#]+)*")
_SENTENCES = re.compile(r"[^.!?\n]+")
# Capitalised words that are part of the rubric's own vocabulary or of ordinary English.
_COMMON_NAMES = frozenset({"i", "star", "situation", "task", "action", "result", "you", "your"})
_REPEAT_OVERLAP = 0.8


def norm_text(text: str) -> str:
    """Case-folded text with invisible characters removed and whitespace collapsed to one space."""
    kept = "".join(
        c for c in unicodedata.normalize("NFKC", text) if unicodedata.category(c) != "Cf"
    )
    return " ".join(kept.casefold().split())


def quote_in(answer: str, quote: str) -> bool:
    """Is `quote` a verbatim run of `answer`? Case and whitespace are ignored, nothing else is."""
    needle = norm_text(quote)
    return len(needle) >= 3 and REDACTION.casefold() not in needle and needle in norm_text(answer)


def _proper_names(text: str) -> set[str]:
    """Capitalised words that do not start a sentence, folded."""
    names: set[str] = set()
    for sentence in _SENTENCES.findall(text):
        tokens = sentence.split()
        for token in tokens[1:]:
            for match in _NAME.finditer(token.strip("\"'()[],;:")):
                name = match.group(0)
                if name.casefold() not in _COMMON_NAMES:
                    names.add(name.casefold())
    return names


_DIGITS = re.compile(r"\d+(?:[.,]\d+)*")
_SMALL_NUMBERS = frozenset({"one", "two", "three", "four", "five"})
# "two sentences" is advice; "two years" is a claim about experience.
_AMOUNT = re.compile(
    r"\b(?:one|two|three|four|five)\s+(?:years?|months?|decades?|percent)\b|\bdecades?\b"
)


def numbers_in(text: str) -> set[str]:
    """The figures a text states: digits, number words from six up, and any number word or
    "decade" that measures time or a percentage."""
    folded = fold(text)
    found = {m.group(0).replace(",", "").rstrip(".") for m in _DIGITS.finditer(folded)}
    found |= {
        NUMBER_WORDS[w] for w in words(folded) if w in NUMBER_WORDS and w not in _SMALL_NUMBERS
    }
    found |= {m.group(0).rstrip("s") for m in _AMOUNT.finditer(folded)}
    return found


def unsupported_terms(text: str, allowed: str) -> list[str]:
    """Numbers and proper names of `text` that are not in `allowed`."""
    have_words = set(words(allowed))
    have_numbers = numbers_in(allowed)
    missing = sorted(n for n in numbers_in(text) if n not in have_numbers)
    missing += [n for n in sorted(_proper_names(text)) if set(words(n)) - have_words]
    return missing


def content_words(text: str) -> set[str]:
    return {w for w in words(text) if len(w) > 2}


def is_repeat(question: str, earlier: Iterable[str]) -> bool:
    """The same question, or one that shares at least 80% of its words with an earlier one."""
    mine = words(question)
    key = set(mine)
    for other in earlier:
        theirs = words(other)
        if mine == theirs:
            return True
        union = key | set(theirs)
        if union and len(key & set(theirs)) / len(union) >= _REPEAT_OVERLAP:
            return True
    return False


def pick_prep_question(
    pool: Sequence[PrepQuestion], asked: Sequence[str], previous: Category | None
) -> PrepQuestion | None:
    """The next question of the user's prep: the first one not asked yet, preferring a different
    category from the previous question, so the session is not all behavioural questions."""
    fresh = [q for q in pool if not is_repeat(q.question, asked)]
    if not fresh:
        return None
    for q in fresh:
        if q.category is not previous:
            return q
    return fresh[0]


def visible_word_count(answer: str) -> int:
    return len(answer.replace(REDACTION, " ").split())


def _clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def ground_feedback(
    *,
    structure: int,
    relevance: int,
    specificity: int,
    star: LlmStar,
    overall: int,
    strengths: Sequence[LlmStrength],
    improvements: Sequence[LlmImprovement],
    answer: str,
    raw_answer: str,
    question: str,
    job_text: str,
) -> tuple[Feedback, int, bool]:
    """The model's feedback with every ungrounded point removed and the scores held to the evidence.

    `answer` is what the model saw (instruction-like sentences removed); `raw_answer` is what the
    candidate wrote, used only to recognise text copied from an instruction they tried to give.
    Returns the feedback, how many points were dropped and whether the scores were capped.
    """
    dropped = 0
    seen: set[tuple[str, ...]] = set()
    kept_strengths: list[Point] = []
    kept_improvements: list[Point] = []
    advice_allowed = f"{answer} {question} {job_text}"

    def unsafe(text: str, quote: str | None) -> bool:
        joined = f"{text} {quote or ''}"
        return (
            is_instruction_like(joined)
            or REDACTION in joined
            or leaked_instruction_text(joined, raw_answer)
        )

    for s in strengths:
        key = tuple(words(s.text))
        if (
            not quote_in(answer, s.quote)
            or unsafe(s.text, s.quote)
            or unsupported_terms(s.text, answer)
            or key in seen
        ):
            dropped += 1
            continue
        seen.add(key)
        kept_strengths.append(Point(text=s.text, quote=s.quote))
    for i in improvements:
        key = tuple(words(i.text))
        if (
            (i.quote is not None and not quote_in(answer, i.quote))
            or unsafe(i.text, i.quote)
            or unsupported_terms(i.text, advice_allowed)
            or key in seen
        ):
            dropped += 1
            continue
        seen.add(key)
        kept_improvements.append(Point(text=i.text, quote=i.quote))

    capped = False

    def cap(score: int, ceiling: int) -> int:
        nonlocal capped
        if score > ceiling:
            capped = True
        return min(score, ceiling)

    star_score = star.score
    if not kept_strengths:
        overall = cap(overall, CAPPED_SCORE)
        structure, relevance, specificity = (
            cap(structure, CAPPED_SCORE),
            cap(relevance, CAPPED_SCORE),
            cap(specificity, CAPPED_SCORE),
        )
        star_score = None if star_score is None else cap(star_score, CAPPED_SCORE)
    if visible_word_count(answer) < SHORT_ANSWER_WORDS:
        structure, specificity = cap(structure, SHORT_CAP), cap(specificity, SHORT_CAP)
        star_score = None if star_score is None else cap(star_score, SHORT_CAP)
    dims = [structure, relevance, specificity] + ([] if star_score is None else [star_score])
    centre = round(fmean(dims))
    final_overall = _clamp(overall, max(1, centre - 1), min(5, centre + 1))
    if final_overall != overall and final_overall < overall:
        capped = True

    if not kept_strengths and not kept_improvements:
        kept_improvements.append(
            _fallback_improvement(structure, relevance, specificity, star_score)
        )
    evidence: list[str] = []
    for point in (*kept_strengths, *kept_improvements):
        if point.quote and point.quote not in evidence:
            evidence.append(point.quote)
    feedback = Feedback(
        structure=structure,
        relevance=relevance,
        specificity=specificity,
        star=Star(
            score=star_score,
            situation=star.situation,
            task=star.task,
            action=star.action,
            result=star.result,
        ),
        overall=final_overall,
        strengths=kept_strengths,
        improvements=kept_improvements,
        evidence=evidence,
    )
    return feedback, dropped, capped


ADVICE: Mapping[str, str] = {
    "structure": "Open with a one-sentence answer, then give your reasoning in a clear order.",
    "relevance": "Tie each part of your answer back to what the question asked.",
    "specificity": "Add a concrete example with a real detail, such as a number or an outcome.",
    "star_completeness": "Cover the situation, your task, what you did and the result.",
}


def _fallback_improvement(
    structure: int, relevance: int, specificity: int, star: int | None
) -> Point:
    scores = {"structure": structure, "relevance": relevance, "specificity": specificity}
    if star is not None:
        scores["star_completeness"] = star
    weakest = min(scores, key=lambda k: scores[k])
    return Point(text=ADVICE[weakest], quote=None)


# --- summary ---


def _mean(values: Sequence[int]) -> float:
    return round(fmean(values), 2)


def compute_averages(turns: Sequence[SummaryTurn]) -> Averages:
    """Per-dimension means over the session's turns, computed here and never by a model.

    STAR completeness is averaged over the behavioural turns only (null when there were none)."""
    star = [t.feedback.star.score for t in turns if t.feedback.star.score is not None]
    return Averages(
        structure=_mean([t.feedback.structure for t in turns]),
        relevance=_mean([t.feedback.relevance for t in turns]),
        specificity=_mean([t.feedback.specificity for t in turns]),
        star_completeness=_mean(star) if star else None,
        overall=_mean([t.feedback.overall for t in turns]),
    )


def _top(turns: Sequence[SummaryTurn], pick: str, *, best_first: bool, limit: int = 3) -> list[str]:
    ordered = sorted(turns, key=lambda t: t.feedback.overall, reverse=best_first)
    out: list[str] = []
    seen: set[tuple[str, ...]] = set()
    # One point per turn first, then second points, so the list spans the session.
    for rank in range(5):
        for t in ordered:
            points: list[Point] = getattr(t.feedback, pick)
            if rank < len(points):
                key = tuple(words(points[rank].text))
                if key not in seen:
                    seen.add(key)
                    out.append(points[rank].text)
                    if len(out) == limit:
                        return out
    return out


def top_strengths(turns: Sequence[SummaryTurn]) -> list[str]:
    return _top(turns, "strengths", best_first=True)


def top_improvements(turns: Sequence[SummaryTurn]) -> list[str]:
    return _top(turns, "improvements", best_first=False)


def weakest_dimensions(averages: Averages) -> list[str]:
    scores = {
        "structure": averages.structure,
        "relevance": averages.relevance,
        "specificity": averages.specificity,
    }
    if averages.star_completeness is not None:
        scores["star_completeness"] = averages.star_completeness
    return sorted(scores, key=lambda k: scores[k])


def template_next_steps(averages: Averages) -> list[str]:
    steps = [ADVICE[d] for d in weakest_dimensions(averages)]
    steps.append("Practise another mock interview and compare your scores with this one.")
    steps.append("Rehearse your answers aloud and keep each to about two minutes.")
    return steps


def template_narrative(averages: Averages, answered: int, ended_early: bool) -> str:
    weakest = weakest_dimensions(averages)[0].replace("_", " ")
    stopped = " You ended the session early." if ended_early else ""
    return (
        f"You answered {answered} question{'s' if answered != 1 else ''} with an average overall "
        f"score of {averages.overall} out of 5.{stopped} Your weakest area was {weakest}."
    )


def summary_allowed_text(
    turns: Sequence[SummaryTurn], averages: Averages, job_title: str, company: str | None
) -> str:
    """Everything a narrative or next step may name: the feedback, the job and the figures."""
    parts = [job_title, company or "", str(len(turns)), "1 5"]
    parts += [str(v) for v in averages.model_dump().values() if v is not None]
    for t in turns:
        parts += [p.text for p in (*t.feedback.strengths, *t.feedback.improvements)]
        parts += [t.question]
    return " ".join(parts)
