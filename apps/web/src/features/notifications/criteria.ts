import {
  EMPLOYMENT_TYPE_LABELS,
  SENIORITY_LABELS,
  WORK_MODE_LABELS,
  type EmploymentType,
  type JobFilters,
  type Seniority,
  type WorkMode,
} from "@/features/jobs/search-params";

import type { SavedSearchCriteria, SavedSearchFrequency } from "./queries";

export const FREQUENCY_LABELS: Record<SavedSearchFrequency, string> = {
  INSTANT: "Instantly, as jobs appear",
  DAILY: "Daily digest",
  WEEKLY: "Weekly digest",
  OFF: "Off (keep it, send nothing)",
};

export const FREQUENCIES: SavedSearchFrequency[] = ["DAILY", "WEEKLY", "INSTANT", "OFF"];

/** The part of the job search a saved search keeps (not the employer or the posting window: see ADR 0028). */
export function filtersToCriteria(filters: JobFilters): SavedSearchCriteria {
  const salary = filters.minSalary ? Number(filters.minSalary) : undefined;
  return {
    q: filters.q || undefined,
    workMode: filters.workMode.length ? filters.workMode : undefined,
    employmentType: filters.employmentType.length ? filters.employmentType : undefined,
    seniority: filters.seniority.length ? filters.seniority : undefined,
    country: filters.country ? [filters.country] : undefined,
    location: filters.location || undefined,
    minSalary: salary,
    salaryCurrency: salary ? filters.salaryCurrency : undefined,
  };
}

/** True when nothing would narrow the search: core-api refuses to save it (it would match every job). */
export function criteriaAreEmpty(criteria: SavedSearchCriteria): boolean {
  return (
    !criteria.q &&
    !criteria.location &&
    !criteria.workMode?.length &&
    !criteria.employmentType?.length &&
    !criteria.seniority?.length &&
    !criteria.country?.length &&
    !criteria.minSalary
  );
}

/** "golang, Remote, Senior, NG, from USD 50,000": the search in words, for the list and the default name. */
export function describeCriteria(criteria: SavedSearchCriteria): string {
  const parts: string[] = [];
  if (criteria.q) parts.push(criteria.q);
  parts.push(...(criteria.workMode ?? []).map((v) => WORK_MODE_LABELS[v as WorkMode] ?? v));
  parts.push(...(criteria.employmentType ?? []).map((v) => EMPLOYMENT_TYPE_LABELS[v as EmploymentType] ?? v));
  parts.push(...(criteria.seniority ?? []).map((v) => SENIORITY_LABELS[v as Seniority] ?? v));
  if (criteria.location) parts.push(criteria.location);
  parts.push(...(criteria.country ?? []));
  if (criteria.minSalary) {
    parts.push(`from ${criteria.salaryCurrency ?? ""} ${criteria.minSalary.toLocaleString("en-US")}`.replace("  ", " "));
  }
  return parts.join(", ");
}

/** The jobs page URL that shows a saved search's results. */
export function searchHref(criteria: SavedSearchCriteria): string {
  const params = new URLSearchParams();
  if (criteria.q) params.set("q", criteria.q);
  criteria.workMode?.forEach((v) => params.append("workMode", v));
  criteria.employmentType?.forEach((v) => params.append("employmentType", v));
  criteria.seniority?.forEach((v) => params.append("seniority", v));
  criteria.country?.forEach((v) => params.set("country", v));
  if (criteria.location) params.set("location", criteria.location);
  if (criteria.minSalary) {
    params.set("minSalary", String(criteria.minSalary));
    params.set("salaryCurrency", criteria.salaryCurrency ?? "USD");
  }
  const query = params.toString();
  return query ? `/jobs?${query}` : "/jobs";
}
