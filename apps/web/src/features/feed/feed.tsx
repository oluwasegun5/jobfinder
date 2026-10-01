"use client";

import Link from "next/link";
import type { ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { FeedCard } from "./feed-card";
import { useFeed, type EmptyReason } from "./queries";

const linkClass = "underline underline-offset-4";

function EmptyState({ reason, onRetry }: { reason: EmptyReason | undefined; onRetry: () => void }) {
  let title: string;
  let body: ReactNode;
  switch (reason) {
    case "NO_RESUME":
      title = "Upload your resume to see matches";
      body = (
        <>
          Your feed is ranked against your resume. <Link href="/profile/resumes" className={linkClass}>Upload a resume</Link>
        </>
      );
      break;
    case "NO_PREFERENCES":
      title = "Set your job preferences";
      body = (
        <>
          Tell us what you are looking for and we will start matching. <Link href="/profile/preferences" className={linkClass}>Set preferences</Link>
        </>
      );
      break;
    case "RESUME_PROCESSING":
      title = "Your resume is still being analysed";
      body = (
        <>
          Matches appear as soon as it is ready, usually within a minute.{" "}
          <button type="button" onClick={onRetry} className={linkClass}>
            Check again
          </button>
        </>
      );
      break;
    default:
      title = "No matches yet";
      body = (
        <>
          Nothing fits your preferences right now, or you have dealt with everything we found. New jobs arrive every day.{" "}
          <Link href="/jobs" className={linkClass}>Search all jobs</Link> or <Link href="/profile/preferences" className={linkClass}>adjust your preferences</Link>
        </>
      );
  }
  return (
    <div className="flex flex-col gap-1 rounded-xl border p-6">
      <h2 className="text-base font-medium">{title}</h2>
      <p className="text-sm text-muted-foreground">{body}</p>
    </div>
  );
}

/** The "For you" feed: the user's matches, best first, with why, and what they can do about each. */
export function Feed() {
  const feed = useFeed();
  const pages = feed.data?.pages ?? [];
  const items = pages.flatMap((page) => page.items ?? []);
  const problem = feed.error instanceof ApiProblem ? feed.error.problem : undefined;
  const failedFirst = feed.isError && items.length === 0;
  const failedMore = feed.isError && items.length > 0;

  return (
    <div className="flex flex-col gap-4">
      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {feed.isPending
          ? "Loading your matches"
          : failedFirst
            ? ""
            : items.length === 0
              ? ""
              : `${items.length} match${items.length === 1 ? "" : "es"}, best first`}
      </div>
      {failedFirst && (
        <>
          <FormError>{problemMessage(problem, "Could not load your matches. Try again.")}</FormError>
          <div>
            <Button variant="outline" onClick={() => void feed.refetch()}>
              Try again
            </Button>
          </div>
        </>
      )}
      {!feed.isPending && !feed.isError && items.length === 0 && (
        <EmptyState reason={pages[0]?.emptyReason} onRetry={() => void feed.refetch()} />
      )}
      {items.length > 0 && (
        <ul className="flex flex-col gap-3" aria-label="Your matches">
          {items.map((item) => (
            <FeedCard key={item.job?.id} item={item} />
          ))}
        </ul>
      )}
      {failedMore && <FormError>{problemMessage(problem, "Could not load more matches. Try again.")}</FormError>}
      {feed.hasNextPage && (
        <div>
          <Button variant="outline" disabled={feed.isFetchingNextPage} onClick={() => void feed.fetchNextPage()}>
            {feed.isFetchingNextPage ? "Loading" : "Load more"}
          </Button>
        </div>
      )}
    </div>
  );
}
