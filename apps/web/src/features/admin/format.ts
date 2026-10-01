/** "3 min ago", "2 h ago", "5 d ago", or the date for anything older than a month. */
export function formatAgo(iso: string | undefined, now: Date = new Date()): string | null {
  if (!iso) return null;
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return null;
  const seconds = Math.round((now.getTime() - then.getTime()) / 1000);
  if (seconds < 0) return formatUntil(iso, now);
  if (seconds < 60) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days < 31) return `${days} d ago`;
  return then.toLocaleDateString("en", { year: "numeric", month: "short", day: "numeric" });
}

/** "in 5 min", "in 3 h", "due now" for a time that has passed, or the date a long way off. */
export function formatUntil(iso: string | undefined, now: Date = new Date()): string | null {
  if (!iso) return null;
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return null;
  const seconds = Math.round((then.getTime() - now.getTime()) / 1000);
  if (seconds <= 0) return "due now";
  const minutes = Math.ceil(seconds / 60);
  if (minutes < 60) return `in ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `in ${hours} h`;
  return then.toLocaleDateString("en", { year: "numeric", month: "short", day: "numeric" });
}

/** An exact local timestamp, for tooltips and the history table. */
export function formatDateTime(iso: string | undefined): string {
  if (!iso) return "-";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleString("en", { dateStyle: "medium", timeStyle: "medium" });
}

/** "850 ms", "12 s", "3 min 05 s"; "running" while the run has not finished. */
export function formatDuration(startedAt: string | undefined, finishedAt: string | undefined): string {
  if (!startedAt || !finishedAt) return "running";
  const ms = new Date(finishedAt).getTime() - new Date(startedAt).getTime();
  if (Number.isNaN(ms) || ms < 0) return "-";
  if (ms < 1_000) return `${ms} ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds} s`;
  return `${Math.floor(seconds / 60)} min ${String(seconds % 60).padStart(2, "0")} s`;
}

export const HEALTH_LABELS: Record<string, string> = {
  HEALTHY: "Healthy",
  DEGRADED: "Degraded",
  FAILING: "Failing",
  UNKNOWN: "Not run yet",
};

export const SCHEDULE_LABELS: Record<string, string> = {
  SCHEDULED: "Scheduled",
  DISABLED: "Disabled",
  UNAVAILABLE: "Needs an API key",
  SCHEDULER_OFF: "Scheduler is off",
};

export const STATUS_LABELS: Record<string, string> = {
  RUNNING: "Running",
  SUCCEEDED: "Succeeded",
  PARTIAL: "Partial",
  FAILED: "Failed",
};

export const ALERT_LABELS: Record<string, string> = {
  ZERO_JOBS: "No jobs returned",
  ERROR_RATE: "High error rate",
};

/** Dollars with the precision AI calls need: "$0.000024", "$12.50". */
export function formatUsd(value: number | undefined): string {
  return (value ?? 0).toLocaleString("en", { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 6 });
}

/** A count with thousands separators: "1,234,567". */
export function formatCount(value: number | undefined): string {
  return (value ?? 0).toLocaleString("en");
}
