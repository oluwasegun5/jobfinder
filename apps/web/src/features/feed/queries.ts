"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

import { unwrap } from "@/features/profile/queries";
import { api } from "@/lib/api";

import type { components } from "@jobfinder/api-contract";

export type FeedPage = components["schemas"]["FeedPage"];
export type FeedItem = components["schemas"]["FeedItem"];
export type AdjustmentReason = components["schemas"]["AdjustmentReason"];
export type EmptyReason = NonNullable<FeedPage["emptyReason"]>;

/** Jobs per request: a screenful, well under the server's maximum of 50. */
export const FEED_PAGE_SIZE = 20;

export const feedKeys = { all: ["feed"] as const };

/**
 * The "For you" feed, one page at a time: "load more" asks for the page after the last one with the cursor the server
 * handed out (the cursor pins the feedback it was ranked with, so the pages already on screen do not shift).
 * It refetches on mount, so coming back to the page shows the effect of anything done elsewhere, but not on window
 * focus: a refetch would drop a hidden job's "Undo" row from under the reader.
 */
export function useFeed() {
  return useInfiniteQuery({
    queryKey: feedKeys.all,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap(await api.GET("/feed", { params: { query: { limit: FEED_PAGE_SIZE, cursor: pageParam } } })),
    getNextPageParam: (last) => last.nextCursor,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}
