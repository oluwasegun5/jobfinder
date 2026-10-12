/**
 * The 0–100 match-score scale (docs/adr/0043-design-system.md). The only place the band thresholds live; components map a
 * band to its token (`text-score-strong`, `bg-score-strong`, …). Bands describe the match, they are not success or error
 * states, so a low score is shown in a neutral colour.
 */
export type ScoreBand = "low" | "fair" | "strong" | "top";

export const scoreBandLabels: Record<ScoreBand, string> = {
  low: "Low match",
  fair: "Fair match",
  strong: "Strong match",
  top: "Top match",
};

/** Clamp to 0–100 and round, so the number shown and the band always agree. */
export function normalizeScore(score: number): number {
  if (!Number.isFinite(score)) return 0;
  return Math.min(100, Math.max(0, Math.round(score)));
}

export function scoreBand(score: number): ScoreBand {
  const value = normalizeScore(score);
  if (value >= 90) return "top";
  if (value >= 75) return "strong";
  if (value >= 50) return "fair";
  return "low";
}
