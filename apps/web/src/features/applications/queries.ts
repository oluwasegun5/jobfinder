"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { ApiProblem, unwrap } from "@/features/profile/queries";

import { moveCard, toBoard, type ApplicationDetail, type Board, type Status } from "./model";

import type { components } from "@jobfinder/api-contract";

export type CreateApplication = components["schemas"]["CreateRequest"];
export type UpdateApplication = components["schemas"]["UpdateRequest"];
export type ReminderRequest = components["schemas"]["ReminderRequest"];
export type FollowUpRequest = components["schemas"]["FollowUpRequest"];

export const applicationKeys = {
  board: ["applications", "board"] as const,
  detail: (id: string) => ["applications", "detail", id] as const,
};

export function useBoard() {
  return useQuery({
    queryKey: applicationKeys.board,
    queryFn: async () => {
      const result = await api.GET("/applications", { params: { query: { grouped: true } } });
      return toBoard(unwrap(result));
    },
  });
}

type MoveVars = { id: string; to: Status; note?: string };

/**
 * Moves a card with an optimistic update: the card is in its new column at once, and if core-api refuses (409
 * invalid_transition, 404, a dropped connection) the board goes back to exactly what it was and the caller shows why.
 */
export function useMoveApplication() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, to, note }: MoveVars) =>
      unwrap(await api.POST("/applications/{id}/status", { params: { path: { id } }, body: { status: to, note } })),
    onMutate: async ({ id, to }) => {
      await queryClient.cancelQueries({ queryKey: applicationKeys.board });
      const previous = queryClient.getQueryData<Board>(applicationKeys.board);
      if (previous) {
        const next = moveCard(previous, id, to);
        if (next) queryClient.setQueryData(applicationKeys.board, next);
      }
      return { previous };
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(applicationKeys.board, context.previous);
    },
    onSettled: (_data, _error, { id }) => {
      void queryClient.invalidateQueries({ queryKey: applicationKeys.board });
      void queryClient.invalidateQueries({ queryKey: applicationKeys.detail(id) });
    },
  });
}

export function useApplication(id: string) {
  return useQuery({
    queryKey: applicationKeys.detail(id),
    queryFn: async () => unwrap(await api.GET("/applications/{id}", { params: { path: { id } } })),
  });
}

function refresh(queryClient: ReturnType<typeof useQueryClient>, detail: ApplicationDetail) {
  if (detail.id) queryClient.setQueryData(applicationKeys.detail(detail.id), detail);
  void queryClient.invalidateQueries({ queryKey: applicationKeys.board });
}

/** Adds an application: "I applied" from a job, or a job found elsewhere (title required). 200 means it existed. */
export function useCreateApplication() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateApplication) => {
      const result = await api.POST("/applications", { body });
      return { application: unwrap(result), existed: result.response.status === 200 };
    },
    onSuccess: ({ application }) => refresh(queryClient, application),
  });
}

export function useUpdateApplication(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateApplication) => unwrap(await api.PATCH("/applications/{id}", { params: { path: { id } }, body })),
    onSuccess: (detail) => refresh(queryClient, detail),
  });
}

export function useAddReminder(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: ReminderRequest) => unwrap(await api.POST("/applications/{id}/reminders", { params: { path: { id } }, body })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: applicationKeys.detail(id) });
      void queryClient.invalidateQueries({ queryKey: applicationKeys.board });
    },
  });
}

export function useCancelReminder(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (reminderId: string) => {
      const result = await api.DELETE("/applications/{id}/reminders/{reminderId}", { params: { path: { id, reminderId } } });
      if (!result.response.ok) throw new ApiProblem(result.error, result.response.status);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: applicationKeys.detail(id) });
      void queryClient.invalidateQueries({ queryKey: applicationKeys.board });
    },
  });
}

/** Asks for a follow-up email draft. Nothing is stored and nothing is sent: it is text for the person to use. */
export function useFollowUpDraft(id: string) {
  return useMutation({
    mutationFn: async (body: FollowUpRequest) => unwrap(await api.POST("/applications/{id}/follow-up-draft", { params: { path: { id } }, body })),
  });
}
