"use client";

import Link from "next/link";

import { Button } from "@/components/ui/button";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { JobCard } from "./job-card";
import { useSavedJobs } from "./queries";

export function SavedJobs() {
  const saved = useSavedJobs();
  const jobs = saved.data?.pages.flatMap((page) => page.items ?? []) ?? [];
  const problem = saved.error instanceof ApiProblem ? saved.error.problem : undefined;

  return (
    <div className="flex flex-col gap-4">
      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {saved.isPending ? "Loading saved jobs" : saved.isError ? "" : jobs.length === 0 ? "No saved jobs" : `${jobs.length} saved job${jobs.length === 1 ? "" : "s"}`}
      </div>
      {saved.isError && (
        <>
          <FormError>{problemMessage(problem, "Could not load your saved jobs. Try again.")}</FormError>
          <div>
            <Button variant="outline" onClick={() => void saved.refetch()}>
              Try again
            </Button>
          </div>
        </>
      )}
      {!saved.isPending && !saved.isError && jobs.length === 0 && (
        <p className="text-sm text-muted-foreground">
          Jobs you save show up here. <Link href="/jobs" className="underline">Find jobs</Link>
        </p>
      )}
      {jobs.length > 0 && (
        <ul className="flex flex-col gap-3" aria-label="Saved jobs">
          {jobs.map((job) => (
            <JobCard key={job.id} job={{ ...job, saved: true }} variant="saved" />
          ))}
        </ul>
      )}
      {saved.hasNextPage && (
        <div>
          <Button variant="outline" disabled={saved.isFetchingNextPage} onClick={() => void saved.fetchNextPage()}>
            {saved.isFetchingNextPage ? "Loading" : "Load more"}
          </Button>
        </div>
      )}
    </div>
  );
}
