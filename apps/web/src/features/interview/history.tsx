"use client";

import Link from "next/link";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { FailureNotice } from "@/features/tailoring/shared";

import { interviewFailure } from "./errors";
import { formatCredits, formatScore, formatWhen, statusLabel } from "./model";
import { useSessionList } from "./queries";

/** The user's interviews, newest first. */
export function InterviewHistory() {
  const [page, setPage] = useState(0);
  const list = useSessionList(page);

  if (list.isPending) return <p role="status">Loading your interviews…</p>;
  if (list.isError || !list.data) {
    return <FailureNotice failure={interviewFailure(list.error)} onRetry={() => void list.refetch()} retrying={list.isFetching} />;
  }

  const items = list.data.items ?? [];
  const totalPages = list.data.totalPages ?? 1;
  if (items.length === 0 && page === 0) {
    return (
      <Card>
        <CardContent className="flex flex-col gap-3 pt-4">
          <p className="text-sm">You have not practised an interview yet. Pick a job and start one from its page.</p>
          <div>
            <Link
              href="/jobs"
              className="inline-flex h-9 items-center rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground outline-none hover:bg-primary/80 focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              Find a job
            </Link>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-col gap-2" aria-label="Your interviews">
        {items.map((row) => (
          <li key={row.id}>
            <Link
              href={`/interviews/${row.id}`}
              className="flex flex-col gap-1 rounded-lg border px-3 py-3 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <span className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{row.jobTitle ?? "Interview"}</span>
                {row.jobCompany && <span className="text-sm text-muted-foreground">{row.jobCompany}</span>}
                <Badge variant={row.status === "ABANDONED" ? "destructive" : row.status === "COMPLETED" ? "default" : "secondary"}>
                  {statusLabel(row.status)}
                </Badge>
              </span>
              <span className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span>{formatWhen(row.createdAt)}</span>
                <span>
                  {row.turnsAnswered} of {row.maxTurns} answered
                </span>
                {row.overall !== undefined && <span>Overall {formatScore(row.overall)} / 5</span>}
                <span>{formatCredits(row.creditsConsumed)} credits</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
      {totalPages > 1 && (
        <nav aria-label="Pages" className="flex items-center gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(p - 1, 0))}>
            Previous
          </Button>
          <span className="text-sm text-muted-foreground">
            Page {page + 1} of {totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>
            Next
          </Button>
        </nav>
      )}
    </div>
  );
}
