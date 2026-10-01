export const WORK_MODES = ["REMOTE", "HYBRID", "ONSITE"] as const;
export const EMPLOYMENT_TYPES = ["FULL_TIME", "PART_TIME", "CONTRACT", "TEMPORARY", "INTERNSHIP"] as const;
export const SENIORITIES = ["INTERN", "JUNIOR", "MID", "SENIOR", "LEAD", "EXECUTIVE"] as const;
export const POSTED_WITHIN = [1, 3, 7, 14, 30] as const;
export const CURRENCIES = ["USD", "GBP", "EUR", "NGN", "CAD", "AUD", "INR", "ZAR", "KES"] as const;

export type WorkMode = (typeof WORK_MODES)[number];
export type EmploymentType = (typeof EMPLOYMENT_TYPES)[number];
export type Seniority = (typeof SENIORITIES)[number];

export const WORK_MODE_LABELS: Record<WorkMode, string> = { REMOTE: "Remote", HYBRID: "Hybrid", ONSITE: "On-site" };
export const EMPLOYMENT_TYPE_LABELS: Record<EmploymentType, string> = {
  FULL_TIME: "Full-time",
  PART_TIME: "Part-time",
  CONTRACT: "Contract",
  TEMPORARY: "Temporary",
  INTERNSHIP: "Internship",
};
export const SENIORITY_LABELS: Record<Seniority, string> = {
  INTERN: "Intern",
  JUNIOR: "Junior",
  MID: "Mid-level",
  SENIOR: "Senior",
  LEAD: "Lead",
  EXECUTIVE: "Executive",
};

/** What a person can ask the job search for. Everything is a string or a list so it round-trips through the URL. */
export type JobFilters = {
  q: string;
  workMode: WorkMode[];
  employmentType: EmploymentType[];
  seniority: Seniority[];
  /** A two-letter country code, or "" for anywhere. */
  country: string;
  /** A city, or "". */
  location: string;
  /** Yearly-equivalent minimum, digits only, or "". Needs {@link JobFilters.salaryCurrency}. */
  minSalary: string;
  salaryCurrency: string;
  /** One of {@link POSTED_WITHIN} as text, or "". */
  postedWithinDays: string;
  /** Only this employer's jobs (set by "more jobs from this company"). */
  companyId: string;
  /** Find jobs similar to this one instead of searching by keyword. */
  similarTo: string;
};

export const NO_FILTERS: JobFilters = {
  q: "",
  workMode: [],
  employmentType: [],
  seniority: [],
  country: "",
  location: "",
  minSalary: "",
  salaryCurrency: "USD",
  postedWithinDays: "",
  companyId: "",
  similarTo: "",
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function only<T extends string>(values: string[], allowed: readonly T[]): T[] {
  return allowed.filter((value) => values.includes(value));
}

/** Reads the filters out of a page URL, ignoring anything it does not recognise (the URL is user-editable). */
export function filtersFromSearchParams(params: URLSearchParams): JobFilters {
  const country = (params.get("country") ?? "").trim().toUpperCase();
  const minSalary = (params.get("minSalary") ?? "").replace(/\D/g, "");
  const currency = (params.get("salaryCurrency") ?? "").trim().toUpperCase();
  const posted = params.get("postedWithinDays") ?? "";
  const companyId = params.get("companyId") ?? "";
  const similarTo = params.get("similarTo") ?? "";
  return {
    q: (params.get("q") ?? "").trim().slice(0, 200),
    workMode: only(params.getAll("workMode"), WORK_MODES),
    employmentType: only(params.getAll("employmentType"), EMPLOYMENT_TYPES),
    seniority: only(params.getAll("seniority"), SENIORITIES),
    country: /^[A-Z]{2}$/.test(country) ? country : "",
    location: (params.get("location") ?? "").trim().slice(0, 200),
    minSalary,
    salaryCurrency: /^[A-Z]{3}$/.test(currency) ? currency : NO_FILTERS.salaryCurrency,
    postedWithinDays: POSTED_WITHIN.some((days) => String(days) === posted) ? posted : "",
    companyId: UUID.test(companyId) ? companyId : "",
    similarTo: UUID.test(similarTo) ? similarTo : "",
  };
}

/** The page URL's query string for these filters; defaults and empty values are left out. */
export function filtersToQueryString(filters: JobFilters): string {
  const params = new URLSearchParams();
  if (filters.similarTo) params.set("similarTo", filters.similarTo);
  else if (filters.q) params.set("q", filters.q);
  filters.workMode.forEach((value) => params.append("workMode", value));
  filters.employmentType.forEach((value) => params.append("employmentType", value));
  filters.seniority.forEach((value) => params.append("seniority", value));
  if (filters.country) params.set("country", filters.country);
  if (filters.location) params.set("location", filters.location);
  if (filters.minSalary) {
    params.set("minSalary", filters.minSalary);
    params.set("salaryCurrency", filters.salaryCurrency);
  }
  if (filters.postedWithinDays) params.set("postedWithinDays", filters.postedWithinDays);
  if (filters.companyId) params.set("companyId", filters.companyId);
  return params.toString();
}

/** The filters as core-api's query parameters (without the keyword, the page size and the cursor). */
export function filtersToApiQuery(filters: JobFilters) {
  return {
    workMode: filters.workMode.length ? filters.workMode : undefined,
    employmentType: filters.employmentType.length ? filters.employmentType : undefined,
    seniority: filters.seniority.length ? filters.seniority : undefined,
    country: filters.country ? [filters.country] : undefined,
    location: filters.location || undefined,
    minSalary: filters.minSalary ? Number(filters.minSalary) : undefined,
    salaryCurrency: filters.minSalary ? filters.salaryCurrency : undefined,
    postedWithinDays: filters.postedWithinDays ? Number(filters.postedWithinDays) : undefined,
    companyId: filters.companyId || undefined,
  };
}

/** How many filters (not counting the keyword) are narrowing the search, for the filter panel's summary. */
export function activeFilterCount(filters: JobFilters): number {
  return (
    (filters.workMode.length ? 1 : 0) +
    (filters.employmentType.length ? 1 : 0) +
    (filters.seniority.length ? 1 : 0) +
    (filters.country ? 1 : 0) +
    (filters.location ? 1 : 0) +
    (filters.minSalary ? 1 : 0) +
    (filters.postedWithinDays ? 1 : 0) +
    (filters.companyId ? 1 : 0)
  );
}
