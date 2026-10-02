"use client";

import Link from "next/link";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

import { ScoreMeter } from "./feedback-card";
import { formatCredits, type Session } from "./model";

function List({ title, items }: { title: string; items: string[] | undefined }) {
  if (!items || items.length === 0) return null;
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h3 className="text-sm font-semibold">{title}</h3>
      <ul className="list-disc space-y-1 pl-5 text-sm">
        {items.map((item, index) => (
          <li key={`${item}-${index}`}>{item}</li>
        ))}
      </ul>
    </section>
  );
}

/** The end of a session: averages computed by the server, what to keep doing, what to work on, and what it cost. */
export function SummaryView({ session }: { session: Session }) {
  const summary = session.summary;
  if (!summary) return null;
  const averages = summary.averages;
  return (
    <Card aria-label="Interview summary">
      <CardHeader>
        <CardTitle>
          <h2 className="text-lg font-semibold">Your interview summary</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <p className="text-sm text-muted-foreground">
          {summary.turnsAnswered} {summary.turnsAnswered === 1 ? "question" : "questions"} answered. Averages are worked out from your
          scores, not by the interviewer.
        </p>
        {summary.narrative && <p className="text-sm">{summary.narrative}</p>}

        {averages && (
          <dl className="grid gap-3 sm:grid-cols-2" aria-label="Average scores">
            <ScoreMeter label="Structure" value={averages.structure} />
            <ScoreMeter label="Relevance" value={averages.relevance} />
            <ScoreMeter label="Specificity" value={averages.specificity} />
            <ScoreMeter label="STAR completeness" value={averages.starCompleteness} />
            <ScoreMeter label="Overall" value={averages.overall} />
          </dl>
        )}

        <div className="grid gap-5 md:grid-cols-2">
          <List title="Top strengths" items={summary.topStrengths} />
          <List title="Top things to work on" items={summary.topImprovements} />
        </div>
        <List title="Next steps" items={summary.nextSteps} />

        <p className="text-xs text-muted-foreground">
          This interview used {formatCredits(summary.creditsConsumed ?? session.creditsConsumed)} credits in total.
        </p>
        <div className="flex flex-wrap gap-2">
          {session.jobId && (
            <Link
              href={`/jobs/${session.jobId}/interview`}
              className="inline-flex h-9 items-center rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground outline-none hover:bg-primary/80 focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              Practise again
            </Link>
          )}
          <Link
            href="/interviews"
            className="inline-flex h-9 items-center rounded-lg border px-3 text-sm font-medium outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            All interviews
          </Link>
        </div>
      </CardContent>
    </Card>
  );
}
