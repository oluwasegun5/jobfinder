import type { components } from "@jobfinder/api-contract";

import type { ContactData, CvRef, FillData, ScreeningAnswer, UsedDocuments } from "../shared/types";
import { ApiError, authed, authedOk } from "./api";

type Profile = components["schemas"]["ProfileResponse"];
type Pack = components["schemas"]["PackResponse"];
type FileSummary = components["schemas"]["RenderedFileSummary"];
type Resume = components["schemas"]["ResumeResponse"];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value);
}

/** First word is the first name, the rest the last name; a single word leaves the last name empty (for the user). */
export function splitName(fullName: string): { firstName?: string; lastName?: string } {
  const parts = fullName.trim().split(/\s+/).filter(Boolean);
  const [first, ...rest] = parts;
  if (!first) return {};
  return { firstName: first, lastName: rest.length > 0 ? rest.join(" ") : undefined };
}

function httpUrl(value: string | undefined): string | undefined {
  if (!value) return undefined;
  try {
    const url = new URL(value.trim());
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : undefined;
  } catch {
    return undefined;
  }
}

function hostOf(url: string): string {
  return new URL(url).hostname.toLowerCase().replace(/^www\./, "");
}

/** LinkedIn, GitHub and one portfolio/website link from the profile's links. */
export function linksFrom(links: Profile["links"]): Pick<ContactData, "linkedin" | "github" | "portfolio"> {
  const out: Pick<ContactData, "linkedin" | "github" | "portfolio"> = {};
  for (const link of links ?? []) {
    const url = httpUrl(link.url);
    if (!url) continue;
    const host = hostOf(url);
    const label = (link.label ?? "").toLowerCase();
    if (host === "linkedin.com" || host.endsWith(".linkedin.com")) out.linkedin ??= url;
    else if (host === "github.com" || host.endsWith(".github.com")) out.github ??= url;
    else if (/portfolio|website|personal|site|blog/.test(label) || out.portfolio === undefined) out.portfolio ??= url;
  }
  return out;
}

export function contactFrom(profile: Profile, email: string | undefined): ContactData {
  const fullName = profile.fullName?.trim() || undefined;
  return {
    fullName,
    ...(fullName ? splitName(fullName) : {}),
    email: email?.trim() || undefined,
    phone: profile.phone?.trim() || undefined,
    location: profile.location?.trim() || undefined,
    ...linksFrom(profile.links),
  };
}

type Content = Record<string, unknown> | undefined;

/** Salutation, paragraphs, closing and signature of an approved cover letter, as plain text. */
export function coverLetterText(content: Content): string | null {
  if (!content) return null;
  const text = (v: unknown): string => (typeof v === "string" ? v.trim() : "");
  const paragraphs = Array.isArray(content.paragraphs) ? content.paragraphs.map(text).filter(Boolean) : [];
  const parts = [text(content.salutation), ...paragraphs, text(content.closing), text(content.signature)].filter(Boolean);
  return parts.length > 0 && paragraphs.length > 0 ? parts.join("\n\n") : null;
}

/** The answers that have text (a NEEDS_INPUT answer has none and is never filled). */
export function screeningFrom(content: Content): ScreeningAnswer[] {
  if (!content || !Array.isArray(content.answers)) return [];
  const out: ScreeningAnswer[] = [];
  for (const raw of content.answers as unknown[]) {
    if (typeof raw !== "object" || raw === null) continue;
    const a = raw as Record<string, unknown>;
    if (typeof a.id !== "string" || typeof a.question !== "string" || typeof a.answer !== "string") continue;
    if (a.status === "NEEDS_INPUT" || a.answer.trim() === "") continue;
    out.push({ id: a.id, question: a.question, answer: a.answer.trim() });
  }
  return out;
}

export interface PackParts {
  coverLetter: string | null;
  screening: ScreeningAnswer[];
  used: UsedDocuments;
  resumeDocumentId: string | null;
}

/** Only APPROVED documents are used: a draft is the user's work in progress (ADR 0006). */
export function approvedParts(pack: Pack | undefined): PackParts {
  const out: PackParts = { coverLetter: null, screening: [], used: {}, resumeDocumentId: null };
  for (const part of pack?.parts ?? []) {
    const doc = part.document;
    if (part.state !== "READY" || !doc || doc.status !== "APPROVED" || !isUuid(doc.id)) continue;
    if (part.type === "COVER_LETTER") {
      out.coverLetter = coverLetterText(doc.content);
      if (out.coverLetter) out.used.coverLetterDocumentId = doc.id;
    } else if (part.type === "SCREENING_ANSWERS") {
      out.screening = screeningFrom(doc.content);
      if (out.screening.length > 0) out.used.screeningAnswersDocumentId = doc.id;
    } else if (part.type === "TAILORED_RESUME") {
      out.resumeDocumentId = doc.id;
    }
  }
  return out;
}

/**
 * The approved tailored resume's rendered file when one already exists (ATS template first, then the newest), else the
 * primary resume as uploaded. Nothing is rendered here: filling a form must not have side effects.
 */
export function chooseCv(resumeDocumentId: string | null, files: FileSummary[], resumes: Resume[]): CvRef | null {
  if (resumeDocumentId) {
    const rendered = [...files]
      .filter((f) => isUuid(f.id))
      .sort((a, b) => Number(b.template === "ATS") - Number(a.template === "ATS") || (b.createdAt ?? "").localeCompare(a.createdAt ?? ""))[0];
    if (rendered?.id) return { kind: "document", documentId: resumeDocumentId, fileId: rendered.id };
  }
  const primary = resumes.find((r) => r.primary && isUuid(r.id));
  return primary?.id ? { kind: "resume", resumeId: primary.id } : null;
}

/**
 * Everything the form needs, from existing endpoints plus GET /extension/apply-context, which is sent the page URL and
 * nothing else about the page. A page core-api does not know still gets the contact details and the primary CV.
 */
export async function loadFillData(pageUrl: string): Promise<FillData> {
  const [context, profile, me, resumes] = await Promise.all([
    authed((api) => api.GET("/extension/apply-context", { params: { query: { url: pageUrl } } })),
    authedOk((api) => api.GET("/profile")),
    authedOk((api) => api.GET("/auth/me")),
    authedOk((api) => api.GET("/resumes")),
  ]);
  if (context.status !== 200 && context.status !== 404) throw new ApiError(context.status);

  const job = context.data?.job;
  const packSummary = context.data?.packSummary;
  let parts: PackParts = { coverLetter: null, screening: [], used: {}, resumeDocumentId: null };
  if (packSummary?.id && isUuid(packSummary.id)) {
    const packId = packSummary.id;
    const pack = await authedOk((api) => api.GET("/application-packs/{id}", { params: { path: { id: packId } } }));
    parts = approvedParts(pack.data);
  }
  let files: FileSummary[] = [];
  if (parts.resumeDocumentId) {
    const documentId = parts.resumeDocumentId;
    const listed = await authedOk((api) => api.GET("/documents/{id}/files", { params: { path: { id: documentId } } }));
    files = listed.data ?? [];
  }

  const cv = chooseCv(parts.resumeDocumentId, files, resumes.data ?? []);
  // `used` is everything on offer; the content script narrows it to what it really filled before logging.
  const used: UsedDocuments = { ...parts.used, ...(cv?.kind === "document" ? { resumeDocumentId: cv.documentId } : {}) };

  return {
    job: job?.id && isUuid(job.id) ? { id: job.id, title: job.title ?? "", company: job.company ?? "" } : null,
    applicationId: isUuid(context.data?.applicationId) ? (context.data?.applicationId ?? null) : null,
    applicationStatus: context.data?.applicationStatus ?? null,
    packId: packSummary?.id && isUuid(packSummary.id) ? packSummary.id : null,
    contact: contactFrom(profile.data ?? {}, me.data?.email),
    coverLetter: parts.coverLetter,
    screening: parts.screening,
    cv,
    used,
  };
}
