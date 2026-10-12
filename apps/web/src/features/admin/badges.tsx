import { Badge } from "@/components/ui/badge";

import { HEALTH_LABELS, STATUS_LABELS } from "./format";

/** What a source's last run says about it. The word is always shown: colour alone is not the message. */
export function HealthBadge({ health }: { health: string | undefined }) {
  const value = health ?? "UNKNOWN";
  const label = HEALTH_LABELS[value] ?? value;
  if (value === "FAILING") return <Badge variant="destructive">{label}</Badge>;
  if (value === "UNKNOWN") return <Badge variant="outline">{label}</Badge>;
  return <Badge variant={value === "HEALTHY" ? "success" : "warning"}>{label}</Badge>;
}

/** How one run ended. */
export function RunStatusBadge({ status }: { status: string | undefined }) {
  const value = status ?? "RUNNING";
  const label = STATUS_LABELS[value] ?? value;
  if (value === "FAILED") return <Badge variant="destructive">{label}</Badge>;
  if (value === "RUNNING") return <Badge variant="secondary">{label}</Badge>;
  return <Badge variant={value === "SUCCEEDED" ? "success" : "warning"}>{label}</Badge>;
}
