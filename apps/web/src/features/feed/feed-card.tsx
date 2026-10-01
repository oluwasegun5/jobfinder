"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Bookmark, BookmarkCheck, Check, ChevronDown, EyeOff, Undo2 } from "lucide-react";
import Link from "next/link";
import { useId, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { problemMessage } from "@/features/auth/api-errors";
import { JobFacts } from "@/features/jobs/job-card";
import { changeJobState, refreshAfterChange, type StateChange } from "@/features/jobs/queries";
import { ApiProblem } from "@/features/profile/queries";
import { cn } from "@/lib/utils";

import { describeAdjustment } from "./adjustment";
import type { FeedItem } from "./queries";
import { ScoreBadge } from "./score-badge";

type Status = "shown" | "hidden" | "applied";

const FALLBACK_TEXT: Record<string, string> = {
  DAILY_CAP_REACHED: "You have used today's AI allowance, so this job has not been scored by AI yet.",
  LLM_UNAVAILABLE: "AI scoring is unavailable right now, so this job has not been scored by AI yet.",
  LLM_FAILED: "AI could not score this job, so the estimate is shown.",
  JOB_EXPIRED: "This job is no longer listed, so it was not scored by AI.",
};

function Reasons({ item }: { item: FeedItem }) {
  const aiScored = item.scoreSource === "LLM_SCORED";
  const strengths = item.strengths ?? [];
  const gaps = item.gaps ?? [];
  if (!aiScored) {
    return (
      <div className="flex flex-col gap-1 text-sm">
        <p>
          This is an estimate from how closely your resume matches this job&apos;s skills and wording, and how recent it
          is. AI has not scored it yet, so there are no strengths or gaps to show.
        </p>
        {item.fallbackReason && FALLBACK_TEXT[item.fallbackReason] && (
          <p className="text-muted-foreground">{FALLBACK_TEXT[item.fallbackReason]}</p>
        )}
      </div>
    );
  }
  return (
    <div className="grid gap-4 text-sm sm:grid-cols-2">
      <section aria-label="Strengths">
        <h4 className="mb-1 font-medium">Strengths</h4>
        {strengths.length > 0 ? (
          <ul className="list-disc space-y-1 pl-5">
            {strengths.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground">None noted.</p>
        )}
      </section>
      <section aria-label="Gaps">
        <h4 className="mb-1 font-medium">Gaps</h4>
        {gaps.length > 0 ? (
          <ul className="list-disc space-y-1 pl-5">
            {gaps.map((g) => (
              <li key={g}>{g}</li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground">None noted.</p>
        )}
      </section>
    </div>
  );
}

/**
 * One job in the feed. Save, hide and "applied" update the card at once and are sent in order in the background (a
 * failure puts the card back and says so). Hide and applied replace the card with a one-line row that can be undone,
 * so a mis-click costs nothing and the list does not jump.
 */
export function FeedCard({ item }: { item: FeedItem }) {
  const job = item.job ?? {};
  const id = job.id ?? "";
  const title = job.title ?? "Untitled job";
  const queryClient = useQueryClient();
  const [saved, setSaved] = useState(job.saved ?? false);
  const [status, setStatus] = useState<Status>("shown");
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string>();
  const panelId = useId();
  // Changes to one job go out one after another, so an undo can never overtake the change it undoes.
  const queue = useRef<Promise<void>>(Promise.resolve());

  const change = (action: StateChange, apply: () => void, revert: () => void) => {
    setError(undefined);
    apply();
    queue.current = queue.current.then(async () => {
      try {
        await changeJobState(id, action);
        refreshAfterChange(queryClient, id, action);
      } catch (e) {
        revert();
        setError(problemMessage(e instanceof ApiProblem ? e.problem : undefined, "Could not update this job. Try again."));
      }
    });
  };

  const toggleSave = () => {
    const was = saved;
    change(was ? "unsave" : "save", () => setSaved(!was), () => setSaved(was));
  };
  const hide = () => {
    const was = saved;
    change("hide", () => { setStatus("hidden"); setSaved(false); }, () => { setStatus("shown"); setSaved(was); });
  };
  const apply = () => change("apply", () => setStatus("applied"), () => setStatus("shown"));
  const undo = () => {
    const from = status;
    change(from === "hidden" ? "unhide" : "unapply", () => setStatus("shown"), () => setStatus(from));
  };

  if (status !== "shown") {
    return (
      <li>
        <Card size="sm">
          <CardContent className="flex flex-wrap items-center gap-3">
            <span role="status" className="text-sm text-muted-foreground">
              {status === "hidden" ? `Hidden: ${title}` : `Marked as applied: ${title}`}
            </span>
            <Button
              variant="outline"
              size="sm"
              aria-label={status === "hidden" ? `Undo hiding ${title}` : `Undo marking ${title} as applied`}
              onClick={undo}
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

  const moved = describeAdjustment(item.adjustment, item.reasons);
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
            <ScoreBadge item={item} />
          </div>
          <JobFacts job={job} />
          {job.summary && <p className="line-clamp-3 text-sm text-muted-foreground">{job.summary}</p>}
          {moved && <p className="text-sm text-muted-foreground">{moved}</p>}
          <div>
            <button
              type="button"
              aria-expanded={open}
              aria-controls={panelId}
              onClick={() => setOpen((v) => !v)}
              className="inline-flex items-center gap-1 rounded-sm text-sm font-medium underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <ChevronDown className={cn("size-4 transition-transform", open && "rotate-180")} aria-hidden />
              {item.scoreSource === "LLM_SCORED" ? "Why this match" : "About this score"}
              <span className="sr-only"> for {title}</span>
            </button>
            <div id={panelId} hidden={!open} className="mt-2">
              {open && <Reasons item={item} />}
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" aria-pressed={saved} aria-label={`${saved ? "Unsave" : "Save"} ${title}`} onClick={toggleSave}>
              {saved ? <BookmarkCheck /> : <Bookmark />} {saved ? "Saved" : "Save"}
            </Button>
            <Button variant="outline" size="sm" aria-label={`Mark ${title} as applied`} onClick={apply}>
              <Check /> Applied
            </Button>
            <Button variant="ghost" size="sm" aria-label={`Hide ${title}`} onClick={hide}>
              <EyeOff /> Hide
            </Button>
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
