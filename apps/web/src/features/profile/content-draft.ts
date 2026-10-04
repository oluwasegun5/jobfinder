import type { Profile, ProfileRequest, ResumeContent, ResumeContentState } from "./queries";

/**
 * The editable, all-strings form of a profile plus one resume's content. Lists carry a stable `key` (for React)
 * and, for items that came from the parser, their `origIndex` in the parsed CV, which is how a grounding warning
 * (`experience[1].company`) stays attached to the right item after the user removes or adds others.
 */

export const SENIORITIES = ["INTERN", "JUNIOR", "MID", "SENIOR", "LEAD", "EXECUTIVE"] as const;

export type LinkDraft = { key: string; label: string; url: string };
export type ExperienceDraft = {
  key: string;
  origIndex: number | null;
  company: string;
  title: string;
  location: string;
  startDate: string;
  endDate: string;
  isCurrent: boolean;
  /** One bullet per line. */
  bullets: string;
};
export type EducationDraft = {
  key: string;
  origIndex: number | null;
  institution: string;
  degree: string;
  fieldOfStudy: string;
  startDate: string;
  endDate: string;
};
export type ProjectDraft = {
  key: string;
  origIndex: number | null;
  name: string;
  description: string;
  url: string;
  /** Comma separated. */
  technologies: string;
};
export type CertificationDraft = { key: string; name: string; issuer: string; date: string };

export type ProfileDraft = {
  fullName: string;
  headline: string;
  location: string;
  phone: string;
  yearsExperience: string;
  seniority: string;
  links: LinkDraft[];
};

export type Draft = {
  profile: ProfileDraft;
  summary: string;
  experience: ExperienceDraft[];
  education: EducationDraft[];
  /** Comma or line separated. */
  skills: string;
  projects: ProjectDraft[];
  certifications: CertificationDraft[];
};

let counter = 0;
export const newKey = () => `k${++counter}`;

export const blankExperience = (): ExperienceDraft => ({
  key: newKey(),
  origIndex: null,
  company: "",
  title: "",
  location: "",
  startDate: "",
  endDate: "",
  isCurrent: false,
  bullets: "",
});
export const blankEducation = (): EducationDraft => ({
  key: newKey(),
  origIndex: null,
  institution: "",
  degree: "",
  fieldOfStudy: "",
  startDate: "",
  endDate: "",
});
export const blankProject = (): ProjectDraft => ({
  key: newKey(),
  origIndex: null,
  name: "",
  description: "",
  url: "",
  technologies: "",
});
export const blankCertification = (): CertificationDraft => ({ key: newKey(), name: "", issuer: "", date: "" });
export const blankLink = (): LinkDraft => ({ key: newKey(), label: "", url: "" });

/** Rough years of experience: from the earliest role's start year to now. Only a starting point for the user. */
export function estimateYears(content: ResumeContent | undefined, now = new Date()): string {
  const years = (content?.experience ?? [])
    .map((job) => Number.parseInt(job.start_date ?? "", 10))
    .filter((year) => Number.isInteger(year) && year > 1950);
  if (years.length === 0) return "";
  const span = now.getFullYear() - Math.min(...years);
  return span >= 0 && span <= 80 ? String(span) : "";
}

const lines = (text: string) =>
  text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
const commas = (text: string) =>
  text
    .split(/[,\n]/)
    .map((part) => part.trim())
    .filter(Boolean);
const orUndefined = (text: string) => (text.trim() === "" ? undefined : text.trim());

/**
 * Builds the form state. Fields the user has already saved win; for someone who has not saved a profile yet
 * (onboarding), the profile fields are pre-filled from the parsed CV so they only have to review them.
 */
export function toDraft(profile: Profile | undefined, content: ResumeContent | undefined, now = new Date()): Draft {
  const saved = Boolean(profile?.updatedAt);
  const contact = content?.contact;
  const linksFrom = saved ? (profile?.links ?? []) : (contact?.links ?? []);

  return {
    profile: {
      fullName: (saved ? profile?.fullName : contact?.full_name) ?? "",
      headline: (saved ? profile?.headline : content?.headline) ?? "",
      location: (saved ? profile?.location : contact?.location) ?? "",
      phone: (saved ? profile?.phone : contact?.phone) ?? "",
      yearsExperience: saved
        ? profile?.yearsExperience == null
          ? ""
          : String(profile.yearsExperience)
        : estimateYears(content, now),
      seniority: (saved ? profile?.seniority : undefined) ?? "",
      links: linksFrom.map((link) => ({ key: newKey(), label: link.label ?? "", url: link.url })),
    },
    summary: content?.summary ?? "",
    experience: (content?.experience ?? []).map((job, index) => ({
      key: newKey(),
      origIndex: index,
      company: job.company,
      title: job.title,
      location: job.location ?? "",
      startDate: job.start_date ?? "",
      endDate: job.end_date ?? "",
      isCurrent: job.is_current ?? false,
      bullets: (job.bullets ?? []).join("\n"),
    })),
    education: (content?.education ?? []).map((school, index) => ({
      key: newKey(),
      origIndex: index,
      institution: school.institution,
      degree: school.degree ?? "",
      fieldOfStudy: school.field_of_study ?? "",
      startDate: school.start_date ?? "",
      endDate: school.end_date ?? "",
    })),
    skills: (content?.skills ?? []).join(", "),
    projects: (content?.projects ?? []).map((project, index) => ({
      key: newKey(),
      origIndex: index,
      name: project.name,
      description: project.description ?? "",
      url: project.url ?? "",
      technologies: (project.technologies ?? []).join(", "),
    })),
    certifications: (content?.certifications ?? []).map((cert) => ({
      key: newKey(),
      name: cert.name,
      issuer: cert.issuer ?? "",
      date: cert.date ?? "",
    })),
  };
}

function linkRequests(links: LinkDraft[]) {
  return links
    .filter((link) => link.url.trim() !== "")
    .map((link) => ({ label: orUndefined(link.label), url: link.url.trim() }));
}

export function toProfileRequest(draft: Draft): ProfileRequest {
  const { profile } = draft;
  const years = profile.yearsExperience.trim();
  return {
    fullName: orUndefined(profile.fullName),
    headline: orUndefined(profile.headline),
    location: orUndefined(profile.location),
    phone: orUndefined(profile.phone),
    links: linkRequests(profile.links),
    yearsExperience: years === "" ? undefined : Number(years),
    seniority: profile.seniority === "" ? undefined : (profile.seniority as ProfileRequest["seniority"]),
  };
}

/**
 * The content to store. The contact block mirrors the profile fields, so there is one place to edit them; the
 * CV's own email is carried over from what was parsed, since the profile has no email field.
 */
export function toContent(draft: Draft, base: ResumeContent | undefined): ResumeContent {
  const { profile } = draft;
  return {
    contact: {
      full_name: orUndefined(profile.fullName),
      email: base?.contact?.email,
      phone: orUndefined(profile.phone),
      location: orUndefined(profile.location),
      links: linkRequests(profile.links),
    },
    headline: orUndefined(profile.headline),
    summary: orUndefined(draft.summary),
    experience: draft.experience.map((job) => ({
      company: job.company.trim(),
      title: job.title.trim(),
      location: orUndefined(job.location),
      start_date: orUndefined(job.startDate),
      end_date: job.isCurrent ? undefined : orUndefined(job.endDate),
      is_current: job.isCurrent,
      bullets: lines(job.bullets),
    })),
    education: draft.education.map((school) => ({
      institution: school.institution.trim(),
      degree: orUndefined(school.degree),
      field_of_study: orUndefined(school.fieldOfStudy),
      start_date: orUndefined(school.startDate),
      end_date: orUndefined(school.endDate),
    })),
    skills: commas(draft.skills),
    projects: draft.projects.map((project) => ({
      name: project.name.trim(),
      description: orUndefined(project.description),
      url: orUndefined(project.url),
      technologies: commas(project.technologies),
    })),
    certifications: draft.certifications.map((cert) => ({
      name: cert.name.trim(),
      issuer: orUndefined(cert.issuer),
      date: orUndefined(cert.date),
    })),
  };
}

export type WarningSummary = {
  experience: ReadonlySet<number>;
  education: ReadonlySet<number>;
  projects: ReadonlySet<number>;
  /** Skills the parser dropped because the CV text never mentioned them. */
  skills: number;
};

/** Groups the parser's grounding warnings by the section and item they point at. */
export function summariseWarnings(warnings: ResumeContentState["warnings"]): WarningSummary {
  const summary = { experience: new Set<number>(), education: new Set<number>(), projects: new Set<number>(), skills: 0 };
  for (const { path = "" } of warnings ?? []) {
    const match = /^(experience|education|projects)\[(\d+)\]/.exec(path);
    if (match) summary[match[1] as "experience" | "education" | "projects"].add(Number(match[2]));
    else if (path.startsWith("skills[")) summary.skills += 1;
  }
  return summary;
}

/** What to tell the user when a CV could not be read; keyed by core-api's stable `parseError` codes. */
export function parseFailureMessage(code: string | undefined): string {
  switch (code) {
    case "no_extractable_text":
      return "We couldn't find any text in that file. It may be a scanned image.";
    case "unreadable_file":
    case "unsupported_file_type":
      return "We couldn't open that file.";
    case "file_too_large":
    case "empty_file":
      return "That file couldn't be used.";
    case "parser_unavailable":
    case "parse_queue_unavailable":
      return "Our CV reader is unavailable right now.";
    case "ai_daily_cap_reached":
      return "You've reached today's AI limit, so this CV hasn't been read yet.";
    case "insufficient_credits":
      return "You are out of AI credits, so this CV hasn't been read yet.";
    default:
      return "We couldn't read that file.";
  }
}
