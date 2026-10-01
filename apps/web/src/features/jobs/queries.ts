"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { ApiProblem, unwrap } from "@/features/profile/queries";

import { filtersToApiQuery, type JobFilters } from "./search-params";

import type { components } from "@jobfinder/api-contract";

export type JobSummary = components["schemas"]["JobSummary"];
export type JobDetail = components["schemas"]["JobDetail"];
export type JobPage = components["schemas"]["JobPage"];
export type JobListing = components["schemas"]["Listing"];

/** Results per request: a screenful, well under the server's maximum of 50. */
export const PAGE_SIZE = 20;

export const jobKeys = {
  all: ["jobs"] as const,
  search: (filters: JobFilters) => ["jobs", "search", filters] as const,
  detail: (id: string) => ["jobs", "detail", id] as const,
  saved: ["jobs", "saved"] as const,
};

/**
 * Search results, one page at a time: "load more" asks for the page after the last one with the cursor the server
 * handed out. With `similarTo` set the keyword is ignored and the server returns that job's nearest neighbours.
 */
export function useJobSearch(filters: JobFilters) {
  return useInfiniteQuery({
    queryKey: jobKeys.search(filters),
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      const query = { ...filtersToApiQuery(filters), limit: PAGE_SIZE, cursor: pageParam };
      if (filters.similarTo) {
        return unwrap(
          await api.GET("/jobs/{id}/similar", { params: { path: { id: filters.similarTo }, query } }),
        );
      }
      return unwrap(await api.GET("/jobs", { params: { query: { ...query, q: filters.q || undefined } } }));
    },
    getNextPageParam: (last) => last.nextCursor,
  });
}

export function useJob(id: string | undefined) {
  return useQuery({
    queryKey: jobKeys.detail(id ?? ""),
    enabled: Boolean(id),
    queryFn: async () => unwrap(await api.GET("/jobs/{id}", { params: { path: { id: id as string } } })),
  });
}

export function useSavedJobs() {
  return useInfiniteQuery({
    queryKey: jobKeys.saved,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap(await api.GET("/saved-jobs", { params: { query: { limit: PAGE_SIZE, cursor: pageParam } } })),
    getNextPageParam: (last) => last.nextCursor,
  });
}

type StateChange = "save" | "unsave" | "hide" | "unhide";

async function change(id: string, action: StateChange) {
  const params = { params: { path: { id } } };
  const result =
    action === "save"
      ? await api.PUT("/jobs/{id}/save", params)
      : action === "unsave"
        ? await api.DELETE("/jobs/{id}/save", params)
        : action === "hide"
          ? await api.PUT("/jobs/{id}/hide", params)
          : await api.DELETE("/jobs/{id}/hide", params);
  if (!result.response.ok) throw new ApiProblem(result.error, result.response.status);
}

/** What changing a job's state does to the cached job page: saving un-hides it and hiding un-saves it. */
function applyToDetail(job: JobDetail, action: StateChange): JobDetail {
  switch (action) {
    case "save":
      return { ...job, saved: true, hidden: false };
    case "unsave":
      return { ...job, saved: false };
    case "hide":
      return { ...job, saved: false, hidden: true };
    case "unhide":
      return { ...job, hidden: false };
  }
}

function refreshAfterChange(queryClient: QueryClient, id: string, action: StateChange) {
  queryClient.setQueryData<JobDetail>(jobKeys.detail(id), (job) => (job ? applyToDetail(job, action) : job));
  // The saved list is on another page: refetch it when it is next shown. Search results already on screen are left
  // alone (a hidden job must not make the list jump under the reader's hand); they refresh on the next visit.
  void queryClient.invalidateQueries({ queryKey: jobKeys.saved });
  void queryClient.invalidateQueries({ queryKey: ["jobs", "search"], refetchType: "none" });
}

/** Save, un-save, hide or un-hide one job. */
export function useJobState() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: StateChange }) => change(id, action),
    onSuccess: (_, { id, action }) => refreshAfterChange(queryClient, id, action),
  });
}
