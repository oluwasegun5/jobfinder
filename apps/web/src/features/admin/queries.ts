"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { ApiProblem, unwrap } from "@/features/profile/queries";

import type { components } from "@jobfinder/api-contract";

export type AdminSource = components["schemas"]["IngestionSourceResponse"];
export type AdminSourceList = components["schemas"]["IngestionSourceListResponse"];
export type AdminRun = components["schemas"]["IngestionRunResponse"];
export type AdminRunPage = components["schemas"]["IngestionRunPageResponse"];
export type AdminAlert = components["schemas"]["SourceAlertResponse"];
export type AdminCostReport = components["schemas"]["CostReportResponse"];
export type AdminCostRow = components["schemas"]["CostRowResponse"];

/** While any source is running, how often the list is re-read to catch the end of the run. */
export const RUN_POLL_MS = 3_000;
/** Runs per page of the history (the server allows up to 100). */
export const RUNS_PAGE_SIZE = 20;

export const adminKeys = {
  sources: ["admin", "sources"] as const,
  runs: (source: string | undefined, page: number) => ["admin", "runs", source ?? "all", page] as const,
  allRuns: ["admin", "runs"] as const,
  costs: (from: string | undefined, to: string | undefined) => ["admin", "costs", from ?? "default", to ?? "default"] as const,
};

export function useAdminSources() {
  return useQuery({
    queryKey: adminKeys.sources,
    queryFn: async () => unwrap(await api.GET("/admin/ingestion/sources")),
    refetchInterval: (query) => (query.state.data?.items?.some((source) => source.running) ? RUN_POLL_MS : false),
  });
}

/** Switches a source on or off for the scheduler; the answer is the source as it now is. */
export function useSetSourceEnabled() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ code, enabled }: { code: string; enabled: boolean }) =>
      unwrap(
        await api.PUT("/admin/ingestion/sources/{code}/enabled", {
          params: { path: { code } },
          body: { enabled },
        }),
      ),
    onSuccess: (updated) => {
      queryClient.setQueryData<AdminSourceList>(adminKeys.sources, (list) =>
        list ? { ...list, items: list.items?.map((s) => (s.code === updated.code ? updated : s)) } : list,
      );
    },
  });
}

/**
 * Starts a run. The server answers 202 at once; the run itself takes as long as it takes, so the list is refreshed
 * now (the source shows as running) and again a moment later, and then polls on its own while it runs.
 */
export function useStartRun() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (code: string) => {
      const result = await api.POST("/admin/ingestion/sources/{code}/runs", { params: { path: { code } } });
      if (!result.response.ok) throw new ApiProblem(result.error, result.response.status);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.sources });
      void queryClient.invalidateQueries({ queryKey: adminKeys.allRuns });
      setTimeout(() => void queryClient.invalidateQueries({ queryKey: adminKeys.sources }), 1_500);
    },
  });
}

/** One page (zero-based) of the run history, newest first, for one source or all of them. */
export function useAdminRuns(source: string | undefined, page: number) {
  return useQuery({
    queryKey: adminKeys.runs(source, page),
    placeholderData: keepPreviousData,
    queryFn: async () =>
      unwrap(
        await api.GET("/admin/ingestion/runs", {
          params: { query: { source: source || undefined, page, size: RUNS_PAGE_SIZE } },
        }),
      ),
  });
}

/**
 * AI cost by feature, day and model between two UTC days (`yyyy-MM-dd`, both included). Without a range core-api
 * answers with the last seven days.
 */
export function useAdminCosts(from: string | undefined, to: string | undefined) {
  return useQuery({
    queryKey: adminKeys.costs(from, to),
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/admin/billing/costs", { params: { query: { from, to } } })),
  });
}
