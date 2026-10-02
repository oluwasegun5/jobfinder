"use client";

import { Loader2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { useJob } from "@/features/jobs/queries";
import { FailureNotice } from "@/features/tailoring/shared";

import { interviewFailure } from "./errors";
import { DEFAULT_TURNS, TURN_CHOICES } from "./model";
import { usePrep, useStartSession, type StartOutcome } from "./queries";

const selectClass =
  "h-9 w-full max-w-48 rounded-lg border border-input bg-background px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

/**
 * Starts a text mock interview for a job, optionally from an interview prep made for that job (its questions are used
 * first, and the first question then costs nothing).
 */
export function StartInterview({ jobId, prepId }: { jobId: string; prepId?: string }) {
  const router = useRouter();
  const job = useJob(jobId);
  const prep = usePrep(prepId);
  const start = useStartSession();
  const [turns, setTurns] = useState<number>(DEFAULT_TURNS);

  const prepMatches = prep.data ? prep.data.jobId === jobId : undefined;
  const usePrepQuestions = Boolean(prepId) && prep.isSuccess && prepMatches === true;
  const failure = start.error ? interviewFailure(start.error) : undefined;

  const input = { jobId, maxTurns: turns, ...(usePrepQuestions ? { prepId } : {}) };
  const open = ({ session }: StartOutcome) => {
    if (session.id) router.push(`/interviews/${session.id}`);
  };
  const send = () => start.mutate(input, { onSuccess: open });
  // An interview for this job is already open: the same session comes back, at the question it was on.
  const resuming = start.data?.resumed === true;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (start.isPending) return;
    send();
  }

  if (job.isPending) return <p role="status">Loading the job…</p>;
  if (job.isError || !job.data) {
    return (
      <div className="flex flex-col gap-3">
        <FailureNotice failure={interviewFailure(job.error)} onRetry={() => void job.refetch()} retrying={job.isFetching} />
        <Link href="/jobs" className="text-sm underline">
          Back to jobs
        </Link>
      </div>
    );
  }

  const title = job.data.title ?? "this job";
  const company = job.data.company?.name;
  const questionCount = prep.data?.questions?.length;

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold tracking-tight">Practise the interview for {title}</h1>
      <Card>
        <CardHeader>
          <CardTitle>
            <h2 className="text-lg font-semibold">Text mock interview</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit} className="flex flex-col gap-4" aria-label="Start a mock interview" aria-busy={start.isPending}>
            <p className="text-sm text-muted-foreground">
              You will be interviewed for <strong>{title}</strong>
              {company ? ` at ${company}` : ""}. After each answer you get scores for structure, relevance and specificity, a STAR check
              for behavioural questions, and quotes from your own answer. At the end you get a summary.
            </p>

            {prepId && prep.isPending && <p role="status" className="text-sm text-muted-foreground">Checking your interview prep…</p>}
            {usePrepQuestions && (
              <p role="status" className="rounded-lg bg-muted px-3 py-2 text-sm">
                The first question comes from your interview prep{questionCount ? ` (${questionCount} questions)` : ""}, and the next ones
                from it too when they fit.
              </p>
            )}
            {prepId && prep.isError && (
              <p role="alert" className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
                Your interview prep could not be loaded, so this interview will use new questions made for the job.
              </p>
            )}
            {prepId && prep.isSuccess && prepMatches === false && (
              <p role="alert" className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
                That interview prep was made for a different job, so it will not be used. This interview will use new questions made for
                the job.
              </p>
            )}

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="turns">Number of questions</Label>
              <select id="turns" className={selectClass} value={turns} onChange={(e) => setTurns(Number(e.target.value))} disabled={start.isPending}>
                {TURN_CHOICES.map((n) => (
                  <option key={n} value={n}>
                    {n} questions
                  </option>
                ))}
              </select>
            </div>

            <p className="text-xs text-muted-foreground">
              Each question and each answer uses part of your daily AI allowance. Your answers are sent to the interviewer to be scored and
              are kept with the interview so you can read them again; they are not saved in your browser.
            </p>

            <div role="status" aria-live="polite" className="min-h-5 text-sm text-muted-foreground">
              {start.isPending && "Getting your first question…"}
              {resuming && "Resuming your session…"}
            </div>
            <FailureNotice failure={failure} onRetry={send} retrying={start.isPending} />

            <div className="flex flex-wrap gap-2">
              <Button type="submit" disabled={start.isPending}>
                {start.isPending ? (
                  <>
                    <Loader2 className="animate-spin" aria-hidden /> Starting…
                  </>
                ) : (
                  "Start interview"
                )}
              </Button>
              <Link
                href={`/jobs/${jobId}`}
                className="inline-flex h-9 items-center rounded-lg border px-3 text-sm font-medium outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                Back to the job
              </Link>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
