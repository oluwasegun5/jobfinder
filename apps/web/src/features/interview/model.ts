import type { components } from "@jobfinder/api-contract";

export type Session = components["schemas"]["SessionView"];
export type Turn = components["schemas"]["TurnView"];
export type Feedback = components["schemas"]["FeedbackView"];
export type Summary = components["schemas"]["SummaryView"];
export type AnswerResult = components["schemas"]["AnswerResult"];
export type SessionRow = components["schemas"]["SessionSummary"];

/** Shown in the counter; core-api enforces its own configured limit and answers `answer_too_long` beyond it. */
export const ANSWER_MAX_CHARS = 4000;
export const DEFAULT_TURNS = 5;
export const TURN_CHOICES = [3, 4, 5, 6, 7, 8] as const;
export const HISTORY_PAGE_SIZE = 10;

export const RUBRIC = [
  { key: "structure", label: "Structure", hint: "Is the answer easy to follow, from set-up to result?" },
  { key: "relevance", label: "Relevance", hint: "Does it answer the question that was asked?" },
  { key: "specificity", label: "Specificity", hint: "Does it give concrete detail, numbers and examples?" },
  { key: "overall", label: "Overall", hint: "The whole answer, taking the three above into account." },
] as const;

export type RubricKey = (typeof RUBRIC)[number]["key"];

export const STAR_PARTS = [
  { key: "situation", label: "Situation" },
  { key: "task", label: "Task" },
  { key: "action", label: "Action" },
  { key: "result", label: "Result" },
] as const;

export const CATEGORY_LABELS: Record<string, string> = {
  behavioral: "Behavioural",
  technical: "Technical",
  role_specific: "Role specific",
};

export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: "In progress",
  COMPLETED: "Completed",
  ABANDONED: "Abandoned",
};

export function categoryLabel(category: string | undefined): string {
  return (category && CATEGORY_LABELS[category]) || "Question";
}

export function statusLabel(status: string | undefined): string {
  return (status && STATUS_LABELS[status]) || "Unknown";
}

/** A fresh key for one attempt at one answer. It matches core-api's `[A-Za-z0-9_-]{8,100}`. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`;
}

/**
 * Keeps one idempotency key for as long as the same answer text is being sent, so a retry after a timeout or a lost
 * response is a repeat that core-api recognises (no second model call, no second charge). A changed text, or a
 * success, starts a new key. The key and the text live in memory only.
 */
export function createKeyKeeper() {
  let last: { text: string; key: string } | undefined;
  return {
    keyFor(text: string): string {
      if (!last || last.text !== text) last = { text, key: newIdempotencyKey() };
      return last.key;
    },
    reset() {
      last = undefined;
    },
  };
}

export function isOver(session: Session | undefined): boolean {
  return session?.status === "COMPLETED" || session?.status === "ABANDONED";
}

/** The questions answered so far out of the session's limit; `current` is the 1-based question being answered. */
export function progressOf(session: Session): { answered: number; total: number; current: number; percent: number } {
  const total = Math.max(session.maxTurns ?? 1, 1);
  const answered = Math.min(session.turnsAnswered ?? 0, total);
  const current = Math.min(answered + 1, total);
  return { answered, total, current, percent: Math.round((answered / total) * 100) };
}

export type Exchange = { question: Turn; answer?: Turn };

/** The transcript as question/answer pairs, in order. The open question has no answer yet. */
export function exchanges(turns: Turn[] | undefined): Exchange[] {
  const out: Exchange[] = [];
  for (const turn of turns ?? []) {
    if (turn.role === "INTERVIEWER") out.push({ question: turn });
    else if (turn.role === "CANDIDATE" && out.length > 0 && !out[out.length - 1].answer) out[out.length - 1].answer = turn;
  }
  return out;
}

export function answerLength(text: string): number {
  return Array.from(text).length;
}

/** Whether an answer can be sent: something written, and not past the limit. */
export function canSubmit(text: string, max = ANSWER_MAX_CHARS): boolean {
  const n = answerLength(text.trim());
  return n > 0 && answerLength(text) <= max;
}

export function personaLine(persona: Session["persona"]): string {
  if (!persona) return "";
  const tone = persona.tone ? `${persona.tone} tone` : "";
  return [persona.interviewer, tone].filter(Boolean).join(", ");
}

export function formatScore(value: number | undefined): string {
  return value === undefined ? "n/a" : Number.isInteger(value) ? String(value) : value.toFixed(1);
}

/** Credits are shown to at most two decimals; a session that used none says so. */
export function formatCredits(value: number | string | undefined): string {
  if (value === undefined) return "0";
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return "0";
  return n === 0 ? "0" : n.toLocaleString("en", { maximumFractionDigits: 2 });
}

export function formatWhen(iso: string | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleString("en", { dateStyle: "medium", timeStyle: "short" });
}
