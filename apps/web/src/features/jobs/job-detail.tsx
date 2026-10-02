"use client";

import { ArrowLeft, Bookmark, BookmarkCheck, Check, ExternalLink, EyeOff, Eye, FilePenLine, Sparkles } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { problemCode, problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { safeHref } from "./format";
import { JobFacts } from "./job-card";
import { useJob, useJobState } from "./queries";

export function JobDetailView({ id }: { id: string }) {
  const job = useJob(id);
  const state = useJobState();
  const [error, setError] = useState<string>();

  if (job.isPending) return <p role="status">Loading job</p>;

  if (job.isError) {
    const notFound = job.error instanceof ApiProblem && job.error.status === 404;
    return (
      <div className="flex flex-col gap-3">
        <FormError>{notFound ? "This job could not be found. It may have been removed." : "Could not load this job. Try again."}</FormError>
        <div className="flex gap-2">
          {!notFound && (
            <Button variant="outline" onClick={() => void job.refetch()}>
              Try again
            </Button>
          )}
          <Link href="/jobs" className="text-sm underline self-center">
            Back to jobs
          </Link>
        </div>
      </div>
    );
  }

  const data = job.data;
  const title = data.title ?? "Untitled job";
  const apply = safeHref(data.applyUrl);
  const act = (action: "save" | "unsave" | "hide" | "unhide" | "apply" | "unapply") => {
    setError(undefined);
    state.mutate(
      { id, action },
      {
        onError: (e) => {
          const problem = e instanceof ApiProblem ? e.problem : undefined;
          setError(problemCode(problem) === "job_not_found" ? "This job is no longer available." : problemMessage(problem, "Could not update this job. Try again."));
        },
      },
    );
  };

  return (
    <article className="flex flex-col gap-4">
      <Link href="/jobs" className="inline-flex items-center gap-1 text-sm underline">
        <ArrowLeft className="size-4" aria-hidden /> Back to jobs
      </Link>
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">{title}</h1>
        <p>{data.company?.name}</p>
        <JobFacts job={data} />
        {data.status && data.status !== "ACTIVE" && <p className="text-sm text-destructive">This job is no longer listed.</p>}
      </header>

      <div className="flex flex-wrap items-center gap-2">
        {apply && (
          <a
            href={apply}
            target="_blank"
            rel="noopener noreferrer nofollow"
            className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground outline-none hover:bg-primary/80 focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            Apply <ExternalLink className="size-4" aria-hidden />
            <span className="sr-only">(opens in a new tab)</span>
          </a>
        )}
        <Link
          href={`/jobs/${id}/tailor`}
          className="inline-flex h-9 items-center gap-1.5 rounded-lg border px-3 text-sm font-medium outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <FilePenLine className="size-4" aria-hidden /> Tailor for this job
        </Link>
        <Button
          variant="outline"
          aria-pressed={data.saved ?? false}
          disabled={state.isPending}
          onClick={() => act(data.saved ? "unsave" : "save")}
        >
          {data.saved ? <BookmarkCheck /> : <Bookmark />} {data.saved ? "Saved" : "Save"}
        </Button>
        <Button
          variant="outline"
          aria-pressed={data.applied ?? false}
          disabled={state.isPending}
          onClick={() => act(data.applied ? "unapply" : "apply")}
        >
          <Check /> {data.applied ? "Applied" : "Mark as applied"}
        </Button>
        <Button variant="ghost" disabled={state.isPending} onClick={() => act(data.hidden ? "unhide" : "hide")}>
          {data.hidden ? <Eye /> : <EyeOff />} {data.hidden ? "Unhide" : "Hide"}
        </Button>
        {data.similarAvailable && (
          <Link
            href={`/jobs?similarTo=${id}`}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg border px-3 text-sm font-medium outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            <Sparkles className="size-4" aria-hidden /> Similar jobs
          </Link>
        )}
      </div>
      <FormError>{error}</FormError>

      {data.skills && data.skills.length > 0 && (
        <section aria-labelledby="skills-heading" className="flex flex-col gap-2">
          <h2 id="skills-heading" className="text-base font-medium">
            Skills
          </h2>
          <ul className="flex flex-wrap gap-2 text-sm">
            {data.skills.map((skill) => (
              <li key={skill} className="rounded-full border px-2.5 py-0.5">
                {skill}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="description-heading" className="flex flex-col gap-2">
        <h2 id="description-heading" className="text-base font-medium">
          Description
        </h2>
        {/* Plain text on purpose: scraped descriptions are never rendered as HTML. */}
        <p className="whitespace-pre-line text-sm leading-relaxed">{data.description || "No description was provided."}</p>
      </section>

      {data.listings && data.listings.length > 0 && (
        <section aria-labelledby="sources-heading" className="flex flex-col gap-2">
          <h2 id="sources-heading" className="text-base font-medium">
            Where this job was listed
          </h2>
          <ul className="flex flex-col gap-2">
            {data.listings.map((listing, index) => {
              const href = safeHref(listing.url);
              const credit = safeHref(listing.attribution?.url);
              return (
                <li key={`${listing.source}-${index}`}>
                  <Card size="sm">
                    <CardContent className="flex flex-col gap-1 text-sm">
                      <span>
                        {href ? (
                          <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="underline">
                            {listing.source}
                          </a>
                        ) : (
                          listing.source
                        )}
                      </span>
                      {(listing.attribution?.text || listing.attribution?.name) && (
                        <span className="text-muted-foreground">
                          {listing.attribution?.text ?? listing.attribution?.name}
                          {credit && (
                            <>
                              {" "}
                              <a href={credit} target="_blank" rel="noopener noreferrer nofollow" className="underline">
                                {listing.attribution?.name ?? "Source"}
                              </a>
                            </>
                          )}
                        </span>
                      )}
                    </CardContent>
                  </Card>
                </li>
              );
            })}
          </ul>
        </section>
      )}
    </article>
  );
}
