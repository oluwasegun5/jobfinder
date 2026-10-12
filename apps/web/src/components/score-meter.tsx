import { normalizeScore, scoreBand, scoreBandLabels, type ScoreBand } from "@/lib/score-scale";
import { cn } from "@/lib/utils";

const bandText: Record<ScoreBand, string> = {
  low: "text-score-low",
  fair: "text-score-fair",
  strong: "text-score-strong",
  top: "text-score-top",
};

const bandFill: Record<ScoreBand, string> = {
  low: "bg-score-low",
  fair: "bg-score-fair",
  strong: "bg-score-strong",
  top: "bg-score-top",
};

/**
 * A match score as text first ("82/100" and the band name), with a bar that only reinforces it.
 * Exposed as a meter so screen readers announce the value and the band.
 */
export function ScoreMeter({ score, className }: { score: number; className?: string }) {
  const value = normalizeScore(score);
  const band = scoreBand(value);
  const label = scoreBandLabels[band];

  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <div className="flex items-baseline justify-between gap-3">
        <p className={cn("flex items-baseline gap-0.5 font-semibold tabular-nums", bandText[band])}>
          <span className="text-headline leading-none tracking-[-0.02em]">{value}</span>
          <span className="text-sm font-medium text-muted-foreground">/100</span>
        </p>
        <p className={cn("text-sm font-medium", bandText[band])}>{label}</p>
      </div>
      <div
        role="meter"
        aria-label="Match score"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={value}
        aria-valuetext={`${value} out of 100, ${label.toLowerCase()}`}
        className="h-2 overflow-hidden rounded-full bg-score-track"
      >
        <div className={cn("h-full rounded-full", bandFill[band])} style={{ width: `${value}%` }} />
      </div>
    </div>
  );
}
