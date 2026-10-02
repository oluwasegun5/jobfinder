"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { unwrap } from "@/features/profile/queries";
import { api } from "@/lib/api";

import { HISTORY_PAGE_SIZE, type AnswerResult, type Session } from "./model";

export const interviewKeys = {
  all: ["interview-sessions"] as const,
  session: (id: string) => ["interview-session", id] as const,
  list: (page: number) => ["interview-sessions", "list", page] as const,
  prep: (id: string) => ["interview-prep", id] as const,
};

/** One session with its transcript. Answers live only in this cache (memory); nothing is written to browser storage. */
export function useSession(id: string) {
  return useQuery({
    queryKey: interviewKeys.session(id),
    queryFn: async () => unwrap(await api.GET("/interview-sessions/{id}", { params: { path: { id } } })),
  });
}

export function useSessionList(page: number) {
  return useQuery({
    queryKey: interviewKeys.list(page),
    placeholderData: keepPreviousData,
    queryFn: async () =>
      unwrap(await api.GET("/interview-sessions", { params: { query: { page, size: HISTORY_PAGE_SIZE } } })),
  });
}

/** The prep a session may start from; used to confirm it is for this job and to say how many questions it holds. */
export function usePrep(id: string | undefined) {
  return useQuery({
    queryKey: interviewKeys.prep(id ?? ""),
    enabled: Boolean(id),
    retry: false,
    queryFn: async () => unwrap(await api.GET("/interview-prep/{id}", { params: { path: { id: id as string } } })),
  });
}

export type StartInput = { jobId: string; prepId?: string; maxTurns?: number };

/** Starts a session. One call may include a model call for the first question, so it can take a few seconds. */
export function useStartSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: StartInput) => unwrap(await api.POST("/interview-sessions", { body: input })),
    onSuccess: (session: Session) => {
      if (session.id) queryClient.setQueryData(interviewKeys.session(session.id), session);
      void queryClient.invalidateQueries({ queryKey: interviewKeys.all });
    },
  });
}

/**
 * Sends one answer with its idempotency key. The result carries the session as it now is, so the cache is replaced
 * by it. A failed call leaves the cache alone: the question stays open and the person can send again with the same key.
 */
export function useSubmitAnswer(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ answer, idempotencyKey }: { answer: string; idempotencyKey: string }) =>
      unwrap(await api.POST("/interview-sessions/{id}/answers", { params: { path: { id } }, body: { answer, idempotencyKey } })),
    onSuccess: (result: AnswerResult) => {
      if (result.session) queryClient.setQueryData(interviewKeys.session(id), result.session);
      void queryClient.invalidateQueries({ queryKey: interviewKeys.all });
    },
    // The session may have changed under us (another tab, or abandoned): show what is stored.
    onError: () => void queryClient.invalidateQueries({ queryKey: interviewKeys.session(id) }),
  });
}

/** Ends the session and makes the summary; also what retries a summary that could not be made after the last answer. */
export function useCompleteSession(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => unwrap(await api.POST("/interview-sessions/{id}/complete", { params: { path: { id } } })),
    onSuccess: (session: Session) => {
      queryClient.setQueryData(interviewKeys.session(id), session);
      void queryClient.invalidateQueries({ queryKey: interviewKeys.all });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: interviewKeys.session(id) }),
  });
}
