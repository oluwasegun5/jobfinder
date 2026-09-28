"use client";

import type { ApiClient } from "@jobfinder/api-contract";

import { Badge } from "@/components/ui/badge";

import { useCoreApiHealth } from "./core-api-health";

export function ApiStatus({ client }: { client?: ApiClient }) {
  const { data, isPending, isError } = useCoreApiHealth(client);

  let label: string;
  let variant: "secondary" | "default" | "destructive";
  if (isPending) {
    label = "Checking…";
    variant = "secondary";
  } else if (isError) {
    label = "Unreachable";
    variant = "destructive";
  } else if (data.status === "UP") {
    label = "Operational";
    variant = "default";
  } else {
    label = `Degraded (${data.status})`;
    variant = "destructive";
  }

  return (
    <p className="flex items-center gap-2 text-sm" role="status" aria-live="polite">
      <span className="text-muted-foreground">API</span>
      <Badge variant={variant}>{label}</Badge>
    </p>
  );
}
