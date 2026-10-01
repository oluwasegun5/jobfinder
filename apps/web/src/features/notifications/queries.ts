"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { ApiProblem, unwrap } from "@/features/profile/queries";

import type { components } from "@jobfinder/api-contract";

export type NotificationPreferences = components["schemas"]["NotificationPreferencesView"];
export type NotificationPreferencesRequest = components["schemas"]["NotificationPreferencesRequest"];
export type SavedSearchRequest = components["schemas"]["SavedSearchRequest"];
export type SavedSearchCriteria = components["schemas"]["SavedSearchCriteria"];
export type SavedSearchFrequency = SavedSearchRequest["frequency"];
type SavedSearchView = components["schemas"]["SavedSearchView"];
/** A saved search as core-api always sends it (the generated type marks every response field optional). */
export type SavedSearch = SavedSearchView & {
  id: string;
  name: string;
  criteria: SavedSearchCriteria;
  frequency: SavedSearchFrequency;
};

export function completeSearches(items: SavedSearchView[] | undefined): SavedSearch[] {
  return (items ?? []).flatMap((item) =>
    item.id && item.name !== undefined && item.frequency
      ? [{ ...item, id: item.id, name: item.name, frequency: item.frequency, criteria: item.criteria ?? {} }]
      : [],
  );
}
export type UnsubscribeInfo = components["schemas"]["UnsubscribeInfo"];

export const notificationKeys = {
  preferences: ["notifications", "preferences"] as const,
  savedSearches: ["saved-searches"] as const,
};

export function useNotificationPreferences() {
  return useQuery({
    queryKey: notificationKeys.preferences,
    queryFn: async () => unwrap(await api.GET("/notifications/preferences")),
  });
}

export function useSaveNotificationPreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: NotificationPreferencesRequest) =>
      unwrap(await api.PUT("/notifications/preferences", { body })),
    onSuccess: (preferences) => queryClient.setQueryData(notificationKeys.preferences, preferences),
  });
}

export function useSavedSearches() {
  return useQuery({
    queryKey: notificationKeys.savedSearches,
    queryFn: async () => unwrap(await api.GET("/saved-searches")),
  });
}

export function useCreateSavedSearch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: SavedSearchRequest) => unwrap(await api.POST("/saved-searches", { body })),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: notificationKeys.savedSearches }),
  });
}

export function useUpdateSavedSearch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: SavedSearchRequest }) =>
      unwrap(await api.PUT("/saved-searches/{id}", { params: { path: { id } }, body })),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: notificationKeys.savedSearches }),
  });
}

export function useDeleteSavedSearch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const result = await api.DELETE("/saved-searches/{id}", { params: { path: { id } } });
      if (!result.response.ok) throw new ApiProblem(result.error, result.response.status);
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: notificationKeys.savedSearches }),
  });
}

/** What an unsubscribe link would do (changes nothing). Public: needs no login. */
export function useUnsubscribeInfo(token: string) {
  return useQuery({
    queryKey: ["unsubscribe", token],
    enabled: token !== "",
    retry: false,
    staleTime: Infinity,
    queryFn: async () => unwrap(await api.GET("/notifications/unsubscribe/{token}", { params: { path: { token } } })),
  });
}

/** Does it. Public: the token is the credential. */
export function useUnsubscribe(token: string) {
  return useMutation({
    mutationFn: async () => unwrap(await api.POST("/notifications/unsubscribe/{token}", { params: { path: { token } } })),
  });
}
