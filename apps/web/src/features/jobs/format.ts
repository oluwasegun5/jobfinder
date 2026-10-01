const PERIOD_LABELS: Record<string, string> = { HOUR: "hour", DAY: "day", WEEK: "week", MONTH: "month", YEAR: "year" };

type SalaryLike = { min?: number; max?: number; currency?: string; period?: string } | undefined;

function money(amount: number, currency: string | undefined) {
  try {
    return new Intl.NumberFormat("en", {
      style: "currency",
      currency: currency ?? "USD",
      maximumFractionDigits: 0,
      notation: amount >= 100_000 ? "compact" : "standard",
    }).format(amount);
  } catch {
    // an unknown currency code: show the number and the code
    return `${amount.toLocaleString("en")} ${currency ?? ""}`.trim();
  }
}

/** "$50,000 - $80,000 / year", "from £45/hour", "up to €6K / month"; null when no salary was stated. */
export function formatSalary(salary: SalaryLike): string | null {
  if (!salary || (salary.min === undefined && salary.max === undefined)) return null;
  const period = salary.period ? ` / ${PERIOD_LABELS[salary.period] ?? salary.period.toLowerCase()}` : "";
  const { min, max, currency } = salary;
  if (min !== undefined && max !== undefined && min !== max) return `${money(min, currency)} - ${money(max, currency)}${period}`;
  if (min !== undefined && max === undefined) return `from ${money(min, currency)}${period}`;
  if (max !== undefined && min === undefined) return `up to ${money(max, currency)}${period}`;
  return `${money((min ?? max) as number, currency)}${period}`;
}

/** "today", "yesterday", "5 days ago", "3 weeks ago", or the date for anything older than about 2 months. */
export function formatPosted(iso: string | undefined, now: Date = new Date()): string | null {
  if (!iso) return null;
  const posted = new Date(iso);
  if (Number.isNaN(posted.getTime())) return null;
  const days = Math.floor((now.getTime() - posted.getTime()) / 86_400_000);
  if (days <= 0) return "today";
  if (days === 1) return "yesterday";
  if (days < 14) return `${days} days ago`;
  if (days < 60) return `${Math.floor(days / 7)} weeks ago`;
  return posted.toLocaleDateString("en", { year: "numeric", month: "short", day: "numeric" });
}

/** A country code as its name ("NG" becomes "Nigeria"); the code itself when the runtime does not know it. */
export function countryName(code: string | undefined): string | undefined {
  if (!code) return undefined;
  try {
    return new Intl.DisplayNames(["en"], { type: "region" }).of(code) ?? code;
  } catch {
    return code;
  }
}

/**
 * Only http(s) links are ever rendered as links: an apply or listing URL comes from a third party, and
 * `javascript:` or `data:` must never end up in an href.
 */
export function safeHref(url: string | undefined): string | undefined {
  if (!url) return undefined;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" || parsed.protocol === "http:" ? parsed.href : undefined;
  } catch {
    return undefined;
  }
}
