"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { ApiProblem, unwrap } from "@/features/profile/queries";
import { api } from "@/lib/api";

import type { components } from "@jobfinder/api-contract";

export type Consent = components["schemas"]["ConsentResponse"];

export const privacyKeys = { consent: ["privacy", "consent"] as const };

export function useConsent() {
  return useQuery({
    queryKey: privacyKeys.consent,
    queryFn: async () => unwrap(await api.GET("/me/consent")),
  });
}

/** Gives (true) or withdraws (false) the consent to AI processing. */
export function useSetAiConsent(onChanged?: () => void | Promise<void>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (granted: boolean) =>
      unwrap(granted ? await api.PUT("/me/consent/ai") : await api.DELETE("/me/consent/ai")),
    onSuccess: async (consent) => {
      queryClient.setQueryData(privacyKeys.consent, consent);
      await onChanged?.();
    },
  });
}

/** Fetches the caller's data export (a zip) as a Blob. */
export async function fetchDataExport(): Promise<Blob> {
  const result = await api.GET("/me/export", { parseAs: "blob" });
  if (result.data === undefined || !result.response.ok) throw new ApiProblem(result.error, result.response.status);
  return result.data as Blob;
}

/** Saves a Blob through a temporary link, the way browsers download without navigating away. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/** Permanently deletes the caller's account and everything stored about them. */
export async function deleteAccount(): Promise<void> {
  const result = await api.DELETE("/me");
  if (!result.response.ok) throw new ApiProblem(result.error, result.response.status);
}
