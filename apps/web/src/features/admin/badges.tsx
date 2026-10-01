import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

import { HEALTH_LABELS, STATUS_LABELS } from "./format";

const GOOD = "bg-emerald-100 text-emerald-900 dark:bg-emerald-900/40 dark:text-emerald-200";
const WARN = "bg-amber-100 text-amber-900 dark:bg-amber-900/40 dark:text-amber-200";

/** What a source's last run says about it. The word is always shown: colour alone is not the message. */
export function HealthBadge({ health }: { health: string | undefined }) {
  const value = health ?? "UNKNOWN";
  const label = HEALTH_LABELS[value] ?? value;
  if (value === "FAILING") return <Badge variant="destructive">{label}</Badge>;
  if (value === "UNKNOWN") return <Badge variant="outline">{label}</Badge>;
  return <Badge className={cn(value === "HEALTHY" ? GOOD : WARN)}>{label}</Badge>;
}

/** How one run ended. */
export function RunStatusBadge({ status }: { status: string | undefined }) {
  const value = status ?? "RUNNING";
  const label = STATUS_LABELS[value] ?? value;
  if (value === "FAILED") return <Badge variant="destructive">{label}</Badge>;
  if (value === "RUNNING") return <Badge variant="secondary">{label}</Badge>;
  return <Badge className={cn(value === "SUCCEEDED" ? GOOD : WARN)}>{label}</Badge>;
}
