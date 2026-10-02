import type { components } from "@jobfinder/api-contract";

export type Draft = components["schemas"]["DraftResponse"];
export type Change = components["schemas"]["ChangeView"];
export type Flag = components["schemas"]["FlagView"];
export type Pack = components["schemas"]["PackResponse"];
export type PackPart = components["schemas"]["PartView"];
export type PackRequest = components["schemas"]["PackRequest"];
export type RenderedFile = components["schemas"]["RenderedFileResponse"];

export type PartType = NonNullable<PackPart["type"]>;
export type Tone = NonNullable<PackRequest["tone"]>;
export type Length = NonNullable<PackRequest["length"]>;

export const PART_TYPES: PartType[] = ["TAILORED_RESUME", "COVER_LETTER", "SCREENING_ANSWERS"];

export const PART_LABELS: Record<PartType, string> = {
  TAILORED_RESUME: "Tailored CV",
  COVER_LETTER: "Cover letter",
  SCREENING_ANSWERS: "Screening answers",
};

export const TONE_LABELS: Record<Tone, string> = { FORMAL: "Formal", WARM: "Warm", CONCISE: "Concise" };
export const LENGTH_LABELS: Record<Length, string> = { SHORT: "Short", STANDARD: "Standard", LONG: "Long" };

/** The pack's part of one type, if the pack has it. */
export function partOf(pack: Pack | null | undefined, type: PartType): PackPart | undefined {
  return pack?.parts?.find((part) => part.type === type);
}

/** A copy of the pack with one document (just edited, approved or regenerated) put in place of the one it had. */
export function replaceDocument(pack: Pack, draft: Draft): Pack {
  return {
    ...pack,
    parts: pack.parts?.map((part) => (part.document?.id === draft.id ? { ...part, document: draft } : part)),
  };
}

export function isBlocking(flag: Flag): boolean {
  return flag.severity === "BLOCKING";
}

export function blockingFlags(draft: Draft): Flag[] {
  return (draft.factCheck?.flags ?? []).filter(isBlocking);
}

export function warningFlags(draft: Draft): Flag[] {
  return (draft.factCheck?.flags ?? []).filter((flag) => !isBlocking(flag));
}

/** Flags that belong to one change (the one to reject to make them go away). */
export function flagsForChange(draft: Draft, changeId: string | undefined): Flag[] {
  if (!changeId) return [];
  return (draft.factCheck?.flags ?? []).filter((flag) => flag.changeId === changeId);
}

/** Flags that name no change: they point at text the user wrote or at the posting, and are shown above the diff. */
export function unattachedFlags(draft: Draft): Flag[] {
  return (draft.factCheck?.flags ?? []).filter((flag) => !flag.changeId);
}

export type AnswerView = { id: string; question: string; answer: string; status: string; hint?: string };

export function answersOf(draft: Draft): AnswerView[] {
  const raw = (draft.content as { answers?: unknown } | undefined)?.answers;
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((a): a is Record<string, unknown> => typeof a === "object" && a !== null)
    .map((a) => ({
      id: String(a.id ?? ""),
      question: String(a.question ?? a.id ?? ""),
      answer: typeof a.answer === "string" ? a.answer : "",
      status: String(a.status ?? ""),
      hint: typeof a.hint === "string" ? a.hint : undefined,
    }));
}

export type LetterView = { salutation: string; paragraphs: string[]; closing: string; signature: string };

export function letterOf(draft: Draft): LetterView {
  const c = (draft.content ?? {}) as Record<string, unknown>;
  return {
    salutation: typeof c.salutation === "string" ? c.salutation : "",
    paragraphs: Array.isArray(c.paragraphs) ? c.paragraphs.filter((p): p is string => typeof p === "string") : [],
    closing: typeof c.closing === "string" ? c.closing : "",
    signature: typeof c.signature === "string" ? c.signature : "",
  };
}

/** The whole letter as the plain text to paste into an application. */
export function letterText(letter: LetterView): string {
  return [letter.salutation, ...letter.paragraphs, letter.closing, letter.signature].filter(Boolean).join("\n\n");
}

export function needsInput(draft: Draft): AnswerView[] {
  return draft.type === "SCREENING_ANSWERS" ? answersOf(draft).filter((a) => a.status === "NEEDS_INPUT") : [];
}

export type ApprovalGate = { allowed: boolean; reasons: string[] };

/**
 * Whether a draft may be approved, and if not, why in words. This only mirrors what core-api enforces (it re-checks
 * on approval); it exists so the button is disabled with a reason instead of failing after a click.
 */
export function approvalGate(draft: Draft): ApprovalGate {
  const reasons: string[] = [];
  if (draft.status === "APPROVED") reasons.push("This document is already approved.");
  if (draft.status === "SUPERSEDED") reasons.push("A newer version replaced this one. Approve the newer version.");
  if (draft.status === "GENERATING") reasons.push("This document is still being made.");
  const blocking = Math.max(draft.factCheck?.blocking ?? 0, blockingFlags(draft).length);
  if (blocking > 0 || draft.status === "FACT_CHECK_FAILED") {
    const n = Math.max(blocking, 1);
    reasons.push(
      draft.type === "TAILORED_RESUME"
        ? `${n} blocking fact-check flag${n === 1 ? "" : "s"}: reject or edit the changes that introduce ${n === 1 ? "it" : "them"}.`
        : `${n} blocking fact-check flag${n === 1 ? "" : "s"}: edit the text that introduces ${n === 1 ? "it" : "them"}.`,
    );
  }
  const open = needsInput(draft).length;
  if (open > 0) reasons.push(`${open} question${open === 1 ? " needs" : "s need"} your answer before this can be approved.`);
  return { allowed: reasons.length === 0, reasons };
}

const FLAG_EXPLANATIONS: Record<string, string> = {
  NEW_EMPLOYER: "An employer that is not in your CV.",
  NEW_JOB_TITLE: "A job title that is not in your CV.",
  NEW_INSTITUTION: "A school or university that is not in your CV.",
  NEW_DEGREE: "A degree that is not in your CV.",
  NEW_CERTIFICATION: "A certification that is not in your CV.",
  NEW_DATE_RANGE: "Dates that do not match your CV.",
  NEW_PROJECT: "A project that is not in your CV.",
  NEW_URL: "A web address that is not in your CV.",
  NEW_EMAIL: "An email address that is not in your CV.",
  NEW_PHONE: "A phone number that is not in your CV.",
  CONTACT_CHANGED: "Your contact details were changed.",
  INJECTION_LEAKAGE: "The text looks like it follows an instruction hidden in the job posting.",
  NEW_SKILL: "A skill that is not listed in your CV. Keep it only if you really have it.",
  NEW_METRIC: "A figure or percentage that is not in your CV. Keep it only if it is true.",
  NEW_NUMBER: "A number that is not in your CV. Keep it only if it is true.",
  NEW_YEAR: "A year that is not in your CV.",
  NEW_TERM: "A term that is not in your CV.",
  ENTRY_REMOVED: "An entry from your CV was left out.",
  CHANGED_FIELD: "A field of one of your entries was changed.",
  JOB_TEXT_COPIED: "A long passage was copied from the job posting.",
  JOB_DESCRIPTION_INJECTION:
    "The job posting contained text that tried to give instructions to an AI (for example \"ignore your instructions\"). " +
    "It was removed and ignored, and nothing from it was added to your documents. This is a warning about the posting, not about your CV.",
};

/** A plain-language sentence for a flag code; the server's own message when the code is not known here. */
export function explainFlag(flag: Flag): string {
  return (flag.code && FLAG_EXPLANATIONS[flag.code]) || flag.message || "This needs your attention.";
}

/** A change's unit as a heading: "Summary", "Experience 2", "Skills". */
export function changeTitle(change: Change): string {
  const section = (change.section ?? "").toLowerCase();
  const label = section.charAt(0).toUpperCase() + section.slice(1);
  const match = /\[(\d+)]/.exec(change.path ?? "");
  const place = match ? ` ${Number(match[1]) + 1}` : "";
  const op = change.op === "ADD" ? " (added)" : change.op === "REMOVE" ? " (removed)" : "";
  return `${label}${place}${op}`;
}

/** The text lines of one unit of a resume (a string, a list of skills or an entry), for the side-by-side view. */
export function unitLines(value: unknown): string[] {
  if (value === null || value === undefined || value === "") return [];
  if (typeof value === "string") return [value];
  if (typeof value === "number" || typeof value === "boolean") return [String(value)];
  if (Array.isArray(value)) {
    const items = value.flatMap((v) => unitLines(v));
    return value.every((v) => typeof v === "string") ? [items.join(", ")] : items;
  }
  if (typeof value === "object") {
    const o = value as Record<string, unknown>;
    const lines: string[] = [];
    const head = [o.title ?? o.name ?? o.degree, o.company ?? o.institution ?? o.issuer].filter(
      (v): v is string => typeof v === "string" && v.length > 0,
    );
    if (head.length) lines.push(head.join(" at "));
    const dates = [o.start_date, o.end_date ?? (o.is_current ? "present" : undefined)].filter(
      (v): v is string => typeof v === "string" && v.length > 0,
    );
    if (dates.length) lines.push(dates.join(" to "));
    for (const key of ["field_of_study", "description", "date", "url"]) {
      if (typeof o[key] === "string" && o[key]) lines.push(o[key] as string);
    }
    if (Array.isArray(o.bullets)) for (const b of o.bullets) if (typeof b === "string") lines.push(`• ${b}`);
    if (Array.isArray(o.technologies) && o.technologies.length) lines.push(`Technologies: ${o.technologies.join(", ")}`);
    return lines.length ? lines : [JSON.stringify(value)];
  }
  return [];
}

export function countStates(changes: Change[] | undefined) {
  const list = changes ?? [];
  const accepted = list.filter((c) => c.state === "ACCEPTED").length;
  return { accepted, rejected: list.length - accepted, total: list.length };
}

export function isApproved(draft: Draft | undefined): boolean {
  return draft?.status === "APPROVED";
}
