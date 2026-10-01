import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

import type { FeedItem } from "./queries";

function tone(score: number, aiScored: boolean): string {
  if (!aiScored) return "border-border text-muted-foreground";
  if (score >= 75) return "border-emerald-600/50 text-emerald-700 dark:text-emerald-400";
  if (score >= 50) return "border-amber-600/50 text-amber-700 dark:text-amber-400";
  return "border-border text-muted-foreground";
}

/**
 * The match score as text first (colour only reinforces it): "82/100" with "AI-scored" when the model said it, "~58/100"
 * with "Estimated" when it is the cheaper estimate from skills, wording and recency (the number must not read as a
 * model verdict).
 */
export function ScoreBadge({ item }: { item: Pick<FeedItem, "matchScore" | "scoreSource"> }) {
  const aiScored = item.scoreSource === "LLM_SCORED";
  const score = item.matchScore ?? 0;
  return (
    <div className="flex items-center gap-2">
      <span className={cn("inline-flex items-baseline gap-0.5 rounded-lg border px-2 py-1 text-base font-semibold tabular-nums", tone(score, aiScored))}>
        <span className="sr-only">{aiScored ? "Match score " : "Estimated match score "}</span>
        {aiScored ? "" : "~"}
        {score}
        <span className="text-xs font-normal">/100</span>
      </span>
      <Badge variant={aiScored ? "default" : "outline"}>{aiScored ? "AI-scored" : "Estimated"}</Badge>
    </div>
  );
}
