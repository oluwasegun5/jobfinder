import type { ScreeningAnswer } from "../shared/types";
import { normalise } from "./dom";

/**
 * A screening answer from the application pack fills a form question only when the question matches closely, and never
 * when two answers could fit. "Closely" is either (a) nearly the same words as the question the pack answered, or (b) a
 * fixed, anchored phrasing of one of a handful of common questions. Anything else is left for the user. Work
 * authorization is not in the list on purpose: those questions are never filled (see classify.ts).
 */

const STOPWORDS = new Set([
  "a", "an", "the", "of", "to", "in", "at", "for", "and", "or", "is", "are", "do", "does", "you", "your", "what",
  "this", "that", "with", "on", "be", "as", "it", "we", "our", "i", "my", "how", "why", "can", "will", "would",
  "have", "has",
]);

const PATTERNS: Record<string, RegExp> = {
  WHY_COMPANY_ROLE:
    /^why (do|would|are|did) you (want|like|wish|interested|apply)\b.*\b(work|join|role|position|company|us)\b|^why .*\b(this|our) (role|position|company)\b|^what (excites|interests|attracts|motivates) you (about|to).*\b(role|position|company|us|opportunity)\b/,
  STRENGTHS: /^(what (are|is) )?(your )?(key |main |top |greatest |biggest )?strengths?$/,
  GROWTH_AREA:
    /^what (is|are) (an |the )?areas? (you|that you) (want|would like|hope|plan) to (grow|develop|improve)\b|^(what are )?(your )?areas? (for|of) (growth|development|improvement)$/,
  BIGGEST_ACHIEVEMENT:
    /^(what (is|was) )?(your )?(biggest|greatest|proudest|most significant|top) (professional )?(achievement|accomplishment)s?( to date)?$/,
  NOTICE_PERIOD:
    /^(what (is|s) )?(your )?(current )?(notice period|earliest (possible )?start date|earliest start)$|^when (can|could|would) you (be able to )?start$|^how soon can you start$/,
  SALARY_EXPECTATION:
    /^(what (are|is) )?(your )?(desired|expected|target|minimum) (annual |base )?(salary|compensation|pay)( range)?$|^(salary|compensation|pay) (expectations?|requirements?)$|^what (are|is) your (salary|compensation) (expectations?|requirements?)$/,
  HOW_HEARD:
    /^how did you (hear|learn|find out) about (this|the|our) (job|role|position|opening|opportunity|company|us)$|^how did you hear about us$|^where did you (hear|find|learn) about (this|the|our) (job|role|position|opening|us)$/,
};

/** Question answers that are never used, whatever the form asks. */
const NEVER = new Set(["WORK_AUTHORIZATION"]);

const MAX_LABEL = 300;
const DICE_THRESHOLD = 0.8;

function tokens(text: string): string[] {
  return normalise(text)
    .split(" ")
    .filter((t) => t && !STOPWORDS.has(t));
}

/** Sørensen–Dice coefficient over the sets of content words. */
export function similarity(a: string, b: string): number {
  const x = new Set(tokens(a));
  const y = new Set(tokens(b));
  if (x.size < 3 || y.size < 3) return 0;
  let shared = 0;
  for (const t of x) if (y.has(t)) shared += 1;
  return (2 * shared) / (x.size + y.size);
}

/** The one answer that fits the label, or null. */
export function matchScreening(label: string, answers: readonly ScreeningAnswer[]): ScreeningAnswer | null {
  const text = normalise(label);
  if (!text || text.length > MAX_LABEL) return null;
  const fits = answers.filter((a) => {
    if (NEVER.has(a.id) || a.answer.trim() === "") return false;
    if (normalise(a.question) === text) return true;
    if (similarity(a.question, label) >= DICE_THRESHOLD) return true;
    const pattern = PATTERNS[a.id];
    return pattern !== undefined && pattern.test(text);
  });
  return fits.length === 1 ? (fits[0] ?? null) : null;
}
