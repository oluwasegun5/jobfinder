"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { unwrap } from "@/features/profile/queries";

import type { components } from "@jobfinder/api-contract";

export type Allowance = components["schemas"]["AllowanceResponse"];

export const billingKeys = {
  allowance: ["billing", "allowance"] as const,
};

/** What the signed-in user can still spend on AI today, and when the day's allowance starts afresh (UTC midnight). */
export function useAllowance({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: billingKeys.allowance,
    enabled,
    queryFn: async () => unwrap(await api.GET("/billing/allowance")),
  });
}
