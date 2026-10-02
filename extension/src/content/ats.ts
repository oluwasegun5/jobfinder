import type { AtsName } from "../shared/types";

/** Which ATS a hostname belongs to; mirrors the match patterns in the manifest. */
export function detectAts(hostname: string): AtsName | null {
  const host = hostname.toLowerCase();
  if (/^(job-)?boards\.(eu\.)?greenhouse\.io$/.test(host)) return "greenhouse";
  if (/^jobs\.(eu\.)?lever\.co$/.test(host)) return "lever";
  if (host === "jobs.ashbyhq.com") return "ashby";
  if (/^[a-z0-9-]+(\.[a-z0-9-]+)*\.myworkdayjobs\.com$/.test(host)) return "workday";
  return null;
}

/** What the extension is allowed to fill on each ATS. Anything outside the set is left for the user and listed. */
export type FillKey =
  | "fullName"
  | "firstName"
  | "lastName"
  | "email"
  | "phone"
  | "location"
  | "city"
  | "linkedin"
  | "github"
  | "portfolio"
  | "coverLetter"
  | "cv"
  | "screening";

const CONTACT: readonly FillKey[] = ["fullName", "firstName", "lastName", "email", "phone", "location", "city"];
const LINKS: readonly FillKey[] = ["linkedin", "github", "portfolio"];

/**
 * Greenhouse and Lever (single-page forms) are filled end to end. Ashby: the contact details and links. Workday is a
 * multi-step single-page app: only the contact details of the step that is on screen (docs/adr/0035-chrome-extension.md).
 */
export const ALLOWED: Record<AtsName, ReadonlySet<FillKey>> = {
  greenhouse: new Set<FillKey>([...CONTACT, ...LINKS, "coverLetter", "cv", "screening"]),
  lever: new Set<FillKey>([...CONTACT, ...LINKS, "coverLetter", "cv", "screening"]),
  ashby: new Set<FillKey>([...CONTACT, ...LINKS]),
  workday: new Set<FillKey>(["firstName", "lastName", "email", "phone", "city"]),
};
