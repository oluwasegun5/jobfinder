"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { unwrap } from "@/features/profile/queries";

import type { components } from "@jobfinder/api-contract";

export type Allowance = components["schemas"]["AllowanceResponse"];
export type Catalogue = components["schemas"]["Catalogue"];
export type BillingMe = components["schemas"]["Me"];
export type LedgerLine = components["schemas"]["LedgerLine"];
export type CheckoutRequest = components["schemas"]["CheckoutRequest"];

export const billingKeys = {
  allowance: ["billing", "allowance"] as const,
  plans: ["billing", "plans"] as const,
  me: ["billing", "me"] as const,
  ledger: ["billing", "ledger"] as const,
};

/** What the signed-in user can still spend on AI today, and when the day's allowance starts afresh (UTC midnight). */
export function useAllowance({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: billingKeys.allowance,
    enabled,
    queryFn: async () => unwrap(await api.GET("/billing/allowance")),
  });
}

/** The plans and credit packs, with the prices of the payment providers that are switched on. */
export function usePlans() {
  return useQuery({
    queryKey: billingKeys.plans,
    queryFn: async () => unwrap(await api.GET("/billing/plans")),
  });
}

/** The caller's plan, balance and what this period granted and used. */
export function useBillingMe(options: { refetchInterval?: (query: { state: { data?: BillingMe } }) => number | false } = {}) {
  return useQuery({
    queryKey: billingKeys.me,
    refetchInterval: options.refetchInterval,
    queryFn: async () => unwrap(await api.GET("/billing/me")),
  });
}

/** The caller's own ledger lines, newest first, a page at a time. */
export function useLedger() {
  return useInfiniteQuery({
    queryKey: billingKeys.ledger,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap(await api.GET("/billing/ledger", { params: { query: { limit: 20, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
}

/** Starts a hosted checkout. The caller follows the returned URL: no card data touches this app. */
export function useCheckout() {
  return useMutation({
    mutationFn: async (body: CheckoutRequest) => unwrap(await api.POST("/billing/checkout", { body })),
  });
}

/** Stops renewal at the end of the paid period. Safe to repeat. */
export function useCancelSubscription() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => unwrap(await api.POST("/billing/subscription/cancel")),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: billingKeys.me });
      void queryClient.invalidateQueries({ queryKey: billingKeys.ledger });
    },
  });
}
