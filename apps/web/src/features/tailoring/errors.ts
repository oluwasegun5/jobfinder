import { ApiProblem } from "@/features/profile/queries";

export type FailureKind = "cap" | "conflict" | "unavailable" | "missing" | "validation" | "network" | "other";

export type Failure = {
  kind: FailureKind;
  code?: string;
  message: string;
  /** For `cap`: when the daily AI allowance comes back (ISO-8601). */
  resetsAt?: string;
  /** Whether trying the same thing again can work. */
  retryable: boolean;
  status?: number;
};

type Problem = { code?: string; detail?: string; resetsAt?: string; currentVersion?: number } | undefined;

export function formatWhen(iso: string | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString("en", { dateStyle: "medium", timeStyle: "short" });
}

export function capMessage(resetsAt: string | undefined): string {
  const when = formatWhen(resetsAt);
  return when
    ? `You have used today's AI allowance. It comes back on ${when}. Nothing was lost: what is already made is kept.`
    : "You have used today's AI allowance. It comes back tomorrow. Nothing was lost: what is already made is kept.";
}

/** Words for the stable `code` core-api puts on every problem document. */
const MESSAGES: Record<string, string> = {
  resume_required: "Add and save a CV in your profile first: tailoring works from your primary CV.",
  job_not_found: "This job is no longer available.",
  tailoring_unavailable: "Tailoring is unavailable right now. Try again in a moment.",
  writing_unavailable: "Writing is unavailable right now. Try again in a moment.",
  follow_up_unavailable: "The follow-up writer is unavailable right now. Try again in a moment.",
  follow_up_rejected: "The draft claimed something your CV does not show, so it was discarded. Try again, or write it yourself.",
  fact_check_unavailable: "The fact check is unavailable, so nothing was changed. Try again in a moment.",
  version_conflict: "This changed in another tab. It has been reloaded: look again and repeat your change.",
  fact_check_failed: "There are still blocking fact-check flags, so this cannot be approved yet.",
  answers_incomplete: "Some questions still have no answer. Write them, then approve.",
  already_approved: "This is already approved.",
  document_approved: "An approved document cannot be changed.",
  document_superseded: "A newer version replaced this one.",
  document_generating: "This is still being made. Try again in a moment.",
  document_not_found: "This document no longer exists.",
  document_not_approved: "Approve the document before exporting it.",
  tailoring_in_progress: "A draft for this job is already being made. Try again in a moment.",
  generation_in_progress: "This is already being made. Try again in a moment.",
  pack_in_progress: "The pack is already being made. Try again in a moment.",
  invalid_transition: "That move is not allowed: an application cannot go back to Saved once it has left it.",
  not_applied_yet: "Mark this application as applied before asking for a follow-up email.",
  application_closed: "This application is closed (rejected or withdrawn), so it cannot have reminders.",
  too_many_reminders: "This application already has the maximum number of pending reminders.",
  application_limit_reached: "You are tracking the maximum number of applications. Delete some to add more.",
  title_required: "A title is required.",
  invalid_url: "That link is not valid. Use an http or https address.",
  job_fields_fixed: "The title and company of an application made from a job cannot be changed.",
  invalid_document: "One of the documents cannot be attached to this application.",
  document_job_mismatch: "One of the documents was made for a different job.",
  invalid_pack: "This pack cannot be attached to this application.",
};

/** Turns anything a core-api call can throw into a typed, user-facing failure. Never includes request data. */
export function toFailure(error: unknown, fallback = "Something went wrong. Please try again."): Failure {
  if (!(error instanceof ApiProblem)) {
    return { kind: "network", message: "Could not reach the server. Check your connection and try again.", retryable: true };
  }
  const problem = (typeof error.problem === "object" ? error.problem : undefined) as Problem;
  const code = problem?.code;
  const status = error.status;
  if (status === 429 || code === "ai_daily_cap_reached") {
    return { kind: "cap", code: "ai_daily_cap_reached", message: capMessage(problem?.resetsAt), resetsAt: problem?.resetsAt, retryable: false, status };
  }
  const known = code ? MESSAGES[code] : undefined;
  const detail = typeof problem?.detail === "string" && problem.detail.length > 0 ? problem.detail : undefined;
  const message = known ?? detail ?? fallback;
  const kind: FailureKind =
    status === 503 ? "unavailable" : status === 404 ? "missing" : status === 409 ? "conflict" : status === 400 ? "validation" : "other";
  return { kind, code, message, retryable: status === 503 || status >= 500, status };
}
