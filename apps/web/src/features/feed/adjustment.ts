import type { AdjustmentReason } from "./queries";

const quoted = (example: string | undefined) => (example ? ` (“${example}”)` : "");

const PHRASES: Record<string, (r: AdjustmentReason) => string | undefined> = {
  HIDDEN_SAME_COMPANY: () => "you hid another job at this company",
  HIDDEN_SIMILAR_TITLE: (r) => `you hid a similar job${quoted(r.example)}`,
  SAVED_SAME_COMPANY: () => "you saved another job at this company",
  SAVED_SIMILAR_TITLE: (r) => `you saved a similar job${quoted(r.example)}`,
  APPLIED_SAME_COMPANY: () => "you applied to another job at this company",
  APPLIED_SIMILAR_TITLE: (r) => `you applied to a similar job${quoted(r.example)}`,
};

/** Points as a person reads them: 15, 6.5, never 15.0. */
export function formatPoints(points: number): string {
  return Math.abs(points)
    .toFixed(1)
    .replace(/\.0$/, "");
}

/**
 * Why the feed moved a job, in a sentence ("Ranked lower by 15 points: you hid a similar job (“Java Engineer”)."), or
 * undefined when nothing moved it. The cap entries only say a limit applied and add no words.
 */
export function describeAdjustment(adjustment: number | undefined, reasons: AdjustmentReason[] | undefined): string | undefined {
  if (!adjustment || Math.abs(adjustment) < 0.05) return undefined;
  const parts = (reasons ?? []).map((r) => (r.code ? PHRASES[r.code]?.(r) : undefined)).filter((p): p is string => Boolean(p));
  const direction = adjustment < 0 ? "lower" : "higher";
  const points = formatPoints(adjustment);
  const base = `Ranked ${direction} by ${points} point${points === "1" ? "" : "s"}`;
  return parts.length > 0 ? `${base}: ${parts.join("; ")}.` : `${base}.`;
}
