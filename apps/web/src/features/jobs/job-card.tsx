"use client";

import { Bookmark, BookmarkCheck, EyeOff, Undo2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { problemMessage } from "@/features/auth/api-errors";
import { ApiProblem } from "@/features/profile/queries";

import { countryName, formatPosted, formatSalary } from "./format";
import { useJobState, type JobSummary } from "./queries";
import { EMPLOYMENT_TYPE_LABELS, SENIORITY_LABELS, WORK_MODE_LABELS, type EmploymentType, type Seniority, type WorkMode } from "./search-params";

export function labelOf<T extends string>(labels: Record<T, string>, value: string | undefined): string | undefined {
  return value ? (labels as Record<string, string>)[value] ?? value : undefined;
}

/** City and country, or the posting's own location text when the normalizer could not read it. */
export function placeOf(job: Pick<JobSummary, "city" | "country" | "location">): string | undefined {
  const country = countryName(job.country);
  if (job.city && country) return `${job.city}, ${country}`;
  return job.city ?? job.location ?? country;
}

/** The facts under a job's title: where, how, how senior, what it pays and when it was posted. */
export function JobFacts({ job }: { job: Pick<JobSummary, "city" | "country" | "location" | "workMode" | "employmentType" | "seniority" | "salary" | "postedAt"> }) {
  const place = placeOf(job);
  const salary = formatSalary(job.salary);
  const posted = formatPosted(job.postedAt);
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
      {place && <span>{place}</span>}
      {job.workMode && <Badge variant="secondary">{labelOf<WorkMode>(WORK_MODE_LABELS, job.workMode)}</Badge>}
      {job.employmentType && <Badge variant="outline">{labelOf<EmploymentType>(EMPLOYMENT_TYPE_LABELS, job.employmentType)}</Badge>}
      {job.seniority && <Badge variant="outline">{labelOf<Seniority>(SENIORITY_LABELS, job.seniority)}</Badge>}
      {salary && <span>{salary}</span>}
      {posted && <span>Posted {posted}</span>}
    </div>
  );
}

/**
 * One job in a list. `search` lists offer save and hide; `saved` lists offer only removing the job from the list.
 * Hiding replaces the card with a one-line "Hidden" row that can be undone, so a mis-click costs nothing and the
 * list does not jump.
 */
export function JobCard({ job, variant = "search" }: { job: JobSummary; variant?: "search" | "saved" }) {
  const id = job.id ?? "";
  const title = job.title ?? "Untitled job";
  const [saved, setSaved] = useState(job.saved ?? false);
  const [hidden, setHidden] = useState(false);
  const [removed, setRemoved] = useState(false);
  const [error, setError] = useState<string>();
  const state = useJobState();

  const run = (action: "save" | "unsave" | "hide" | "unhide", done: () => void) => {
    setError(undefined);
    state.mutate(
      { id, action },
      {
        onSuccess: done,
        onError: (e) =>
          setError(problemMessage(e instanceof ApiProblem ? e.problem : undefined, "Could not update this job. Try again.")),
      },
    );
  };

  if (removed) return null;

  if (hidden) {
    return (
      <li>
        <Card size="sm">
          <CardContent className="flex flex-wrap items-center gap-3">
            <span className="text-sm text-muted-foreground">Hidden: {title}</span>
            <Button
              variant="outline"
              size="sm"
              aria-label={`Undo hiding ${title}`}
              disabled={state.isPending}
              onClick={() => run("unhide", () => setHidden(false))}
            >
              <Undo2 /> Undo
            </Button>
            {error && (
              <span role="alert" className="text-sm text-destructive">
                {error}
              </span>
            )}
          </CardContent>
        </Card>
      </li>
    );
  }

  return (
    <li>
      <Card size="sm">
        <CardContent className="flex flex-col gap-2">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div className="min-w-0">
              <h3 className="text-base font-medium">
                <Link
                  href={`/jobs/${id}`}
                  className="rounded-sm outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  {title}
                </Link>
              </h3>
              <p className="text-sm">{job.company?.name}</p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {job.similarity !== undefined && <Badge variant="secondary">{Math.round(job.similarity * 100)}% similar</Badge>}
              {job.applied && <Badge variant="secondary">Applied</Badge>}
              {job.status && job.status !== "ACTIVE" && <Badge variant="destructive">No longer listed</Badge>}
            </div>
          </div>
          <JobFacts job={job} />
          {job.summary && <p className="text-sm text-muted-foreground">{job.summary}</p>}
          <div className="flex flex-wrap items-center gap-2">
            {variant === "search" ? (
              <Button
                variant="outline"
                size="sm"
                aria-pressed={saved}
                aria-label={`${saved ? "Unsave" : "Save"} ${title}`}
                disabled={state.isPending}
                onClick={() => run(saved ? "unsave" : "save", () => setSaved(!saved))}
              >
                {saved ? <BookmarkCheck /> : <Bookmark />} {saved ? "Saved" : "Save"}
              </Button>
            ) : (
              <Button
                variant="outline"
                size="sm"
                aria-label={`Remove ${title} from saved jobs`}
                disabled={state.isPending}
                onClick={() => run("unsave", () => setRemoved(true))}
              >
                <BookmarkCheck /> Remove
              </Button>
            )}
            {variant === "search" && (
              <Button
                variant="ghost"
                size="sm"
                aria-label={`Hide ${title}`}
                disabled={state.isPending}
                onClick={() => run("hide", () => setHidden(true))}
              >
                <EyeOff /> Hide
              </Button>
            )}
            {error && (
              <span role="alert" className="text-sm text-destructive">
                {error}
              </span>
            )}
          </div>
        </CardContent>
      </Card>
    </li>
  );
}
