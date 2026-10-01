"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";

import type { components } from "@jobfinder/api-contract";

export type Profile = components["schemas"]["ProfileResponse"];
export type ProfileRequest = components["schemas"]["ProfileRequest"];
export type Preferences = components["schemas"]["PreferencesResponse"];
export type PreferencesRequest = components["schemas"]["PreferencesRequest"];
export type Resume = components["schemas"]["ResumeResponse"];
export type ResumeContent = components["schemas"]["ResumeContent"];
export type ResumeContentState = components["schemas"]["ResumeContentResponse"];

/** A failed core-api call. `problem` is the RFC 7807 body, for `problemMessage` / `problemFieldErrors`. */
export class ApiProblem extends Error {
  constructor(
    readonly problem: unknown,
    readonly status: number,
  ) {
    super(`core-api responded with HTTP ${status}`);
  }
}

export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined) throw new ApiProblem(result.error, result.response.status);
  return result.data;
}

export const queryKeys = {
  profile: ["profile"] as const,
  preferences: ["preferences"] as const,
  resumes: ["resumes"] as const,
  content: (resumeId: string) => ["resume-content", resumeId] as const,
};

/** How often to re-check a resume that is still being parsed. */
export const PARSE_POLL_MS = 2_000;

export function useProfile() {
  return useQuery({
    queryKey: queryKeys.profile,
    queryFn: async () => unwrap(await api.GET("/profile")),
  });
}

export function useSaveProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: ProfileRequest) => unwrap(await api.PUT("/profile", { body })),
    onSuccess: (profile) => queryClient.setQueryData(queryKeys.profile, profile),
  });
}

export function usePreferences() {
  return useQuery({
    queryKey: queryKeys.preferences,
    queryFn: async () => unwrap(await api.GET("/preferences")),
  });
}

export function useSavePreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: PreferencesRequest) => unwrap(await api.PUT("/preferences", { body })),
    onSuccess: (preferences) => {
      queryClient.setQueryData(queryKeys.preferences, preferences);
      // Saving preferences is what completes onboarding. Update the cached profile now, so the dashboard guard
      // never sees a stale "not onboarded" on its first render, and refetch to pick up anything else.
      queryClient.setQueryData<Profile>(queryKeys.profile, (profile) =>
        profile ? { ...profile, onboardingCompleted: true } : profile,
      );
      return queryClient.invalidateQueries({ queryKey: queryKeys.profile });
    },
  });
}

/** Polls while any resume is still being parsed, so its status badge updates by itself. */
export function useResumes({ pollMs = 3_000 }: { pollMs?: number } = {}) {
  return useQuery({
    queryKey: queryKeys.resumes,
    queryFn: async () => unwrap(await api.GET("/resumes")),
    refetchInterval: (query) => (query.state.data?.some((r) => r.parseStatus === "PENDING") ? pollMs : false),
  });
}

export function useUploadResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (file: File) =>
      unwrap(
        await api.POST("/resumes", {
          // The generated type says `string` for a binary part; the serializer sends the File itself.
          body: { file: file as unknown as string },
          bodySerializer: () => {
            const form = new FormData();
            form.append("file", file);
            return form;
          },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.resumes }),
  });
}

export function useSetPrimaryResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => unwrap(await api.PUT("/resumes/{id}/primary", { params: { path: { id } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.resumes }),
  });
}

export function useDeleteResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { response, error } = await api.DELETE("/resumes/{id}", { params: { path: { id } } });
      if (!response.ok) throw new ApiProblem(error, response.status);
    },
    onSuccess: (_, id) => {
      queryClient.removeQueries({ queryKey: queryKeys.content(id) });
      return queryClient.invalidateQueries({ queryKey: queryKeys.resumes });
    },
  });
}

/**
 * The latest parsed/edited content of a resume. Keeps polling while parsing is pending and nothing has been
 * saved yet, so the review screen never has to assume parsing has finished.
 */
export function useResumeContent(resumeId: string | undefined, { pollMs = PARSE_POLL_MS } = {}) {
  return useQuery({
    queryKey: queryKeys.content(resumeId ?? ""),
    enabled: Boolean(resumeId),
    queryFn: async () =>
      unwrap(await api.GET("/resumes/{id}/content", { params: { path: { id: resumeId as string } } })),
    refetchInterval: (query) => {
      const state = query.state.data;
      return state?.parseStatus === "PENDING" && !state.content ? pollMs : false;
    },
  });
}

export function useSaveResumeContent(resumeId: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: ResumeContent) =>
      unwrap(await api.PUT("/resumes/{id}/content", { params: { path: { id: resumeId as string } }, body })),
    onSuccess: (state) => queryClient.setQueryData(queryKeys.content(state.resumeId ?? resumeId ?? ""), state),
  });
}

export async function fetchDownloadUrl(id: string) {
  return unwrap(await api.GET("/resumes/{id}/download-url", { params: { path: { id } } })).url;
}
