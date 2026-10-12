import { Badge } from "@/components/ui/badge";
import { normalizeScore, scoreBand, type ScoreBand } from "@/lib/score-scale";
import { cn } from "@/lib/utils";

import type { FeedItem } from "./queries";

const bandText: Record<ScoreBand, string> = {
  low: "text-score-low",
  fair: "text-score-fair",
  strong: "text-score-strong",
  top: "text-score-top",
};

/** An estimate is never coloured as a verdict: only a score the model gave takes its band colour. */
function tone(score: number, aiScored: boolean): string {
  if (!aiScored) return "text-muted-foreground";
  return bandText[scoreBand(score)];
}

/**
 * The match score as text first (colour only reinforces it): "82/100" with "AI-scored" when the model said it, "~58/100"
 * with "Estimated" when it is the cheaper estimate from skills, wording and recency (the number must not read as a
 * model verdict).
 */
export function ScoreBadge({ item }: { item: Pick<FeedItem, "matchScore" | "scoreSource"> }) {
  const aiScored = item.scoreSource === "LLM_SCORED";
  const score = normalizeScore(item.matchScore ?? 0);
  return (
    <div className="flex items-center gap-2">
      <span className={cn("inline-flex items-baseline gap-0.5 rounded-lg border bg-card px-2 py-1 text-base font-semibold tabular-nums", tone(score, aiScored))}>
        <span className="sr-only">{aiScored ? "Match score " : "Estimated match score "}</span>
        {aiScored ? "" : "~"}
        {score}
        <span className="text-xs font-normal">/100</span>
      </span>
      <Badge variant={aiScored ? "default" : "outline"}>{aiScored ? "AI-scored" : "Estimated"}</Badge>
    </div>
  );
}
