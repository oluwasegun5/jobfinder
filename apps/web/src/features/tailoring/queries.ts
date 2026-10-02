"use client";

import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { unwrap } from "@/features/profile/queries";

import { replaceDocument, type Draft, type Pack, type PackRequest, type PartType, type RenderedFile } from "./model";

import type { components } from "@jobfinder/api-contract";

export type Operation = components["schemas"]["Operation"];
export type RenderRequest = components["schemas"]["RenderRequest"];
export type WritingRequest = components["schemas"]["WritingRequest"];

/** How often to look again at a pack that another request is still generating. */
export const PACK_POLL_MS = 3_000;

export const tailoringKeys = {
  packForJob: (jobId: string) => ["pack-for-job", jobId] as const,
};

async function fetchPackForJob(jobId: string): Promise<Pack | null> {
  const list = unwrap(await api.GET("/application-packs", { params: { query: { jobId, limit: 1 } } }));
  const first = list.items?.[0];
  if (!first?.id) return null;
  return unwrap(await api.GET("/application-packs/{id}", { params: { path: { id: first.id } } }));
}

/**
 * The job's application pack (newest), or null when none was made yet. This one cached object is the source of truth
 * for every document on the page: edits, approvals and regenerations write their result into it.
 */
export function usePackForJob(jobId: string) {
  return useQuery({
    queryKey: tailoringKeys.packForJob(jobId),
    queryFn: () => fetchPackForJob(jobId),
    refetchInterval: (query) => (query.state.data?.status === "GENERATING" ? PACK_POLL_MS : false),
  });
}

function putPack(queryClient: QueryClient, jobId: string, pack: Pack) {
  queryClient.setQueryData(tailoringKeys.packForJob(jobId), pack);
}

/** Makes (or finds) the pack for a job. One call can take a while: the server runs the model calls in the request. */
export function useCreatePack(jobId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: PackRequest) =>
      unwrap(await api.POST("/jobs/{id}/application-pack", { params: { path: { id: jobId } }, body })),
    onSuccess: (pack) => putPack(queryClient, jobId, pack),
  });
}

/** Makes the parts that failed, were blocked by the daily cap or lost their draft, again. */
export function useRetryPack(jobId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ packId, parts }: { packId: string; parts?: PartType[] }) =>
      unwrap(await api.POST("/application-packs/{id}/retry", { params: { path: { id: packId } }, body: { parts } })),
    onSuccess: (pack) => putPack(queryClient, jobId, pack),
  });
}

/** Writes the letter or the answers again with other options; the pack then follows to the new draft. */
export function useRegenerate(jobId: string, type: Exclude<PartType, "TAILORED_RESUME">) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: WritingRequest) => {
      const params = { params: { path: { id: jobId } }, body };
      return unwrap(
        type === "COVER_LETTER"
          ? await api.POST("/jobs/{id}/cover-letter", params)
          : await api.POST("/jobs/{id}/screening-answers", params),
      );
    },
    onSuccess: () => queryClient.refetchQueries({ queryKey: tailoringKeys.packForJob(jobId) }),
  });
}

/** Accepts or rejects changes, or edits a unit or a text; needs the version the person is looking at. */
export function usePatchDocument(jobId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version, operations }: { id: string; version: number; operations: Operation[] }) =>
      unwrap(await api.PATCH("/documents/{id}", { params: { path: { id } }, body: { version, operations } })),
    onSuccess: (draft: Draft) => {
      queryClient.setQueryData<Pack | null>(tailoringKeys.packForJob(jobId), (pack) => (pack ? replaceDocument(pack, draft) : pack));
    },
    // Someone else's edit, or an approval, got there first: show what is stored now.
    onError: () => void queryClient.invalidateQueries({ queryKey: tailoringKeys.packForJob(jobId) }),
  });
}

export function useApproveDocument(jobId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => unwrap(await api.POST("/documents/{id}/approve", { params: { path: { id } } })),
    onSuccess: (draft: Draft) => {
      queryClient.setQueryData<Pack | null>(tailoringKeys.packForJob(jobId), (pack) => (pack ? replaceDocument(pack, draft) : pack));
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: tailoringKeys.packForJob(jobId) }),
  });
}

/** Renders an approved document to a file and returns a short-lived download link. */
export function useRenderDocument() {
  return useMutation({
    mutationFn: async ({ id, ...body }: RenderRequest & { id: string }): Promise<RenderedFile> =>
      unwrap(await api.POST("/documents/{id}/render", { params: { path: { id } }, body })),
  });
}
