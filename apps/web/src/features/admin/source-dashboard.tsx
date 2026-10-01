"use client";

import { Play } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { problemCode, problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";
import { cn } from "@/lib/utils";

import { HealthBadge, RunStatusBadge } from "./badges";
import { ALERT_LABELS, formatAgo, formatDateTime, formatDuration, formatUntil, SCHEDULE_LABELS } from "./format";
import { NotAllowed, isForbidden } from "./require-admin";
import { useAdminSources, useSetSourceEnabled, useStartRun, type AdminSource } from "./queries";

type Notice = { kind: "info" | "error"; text: string };

function runErrorMessage(code: string, error: unknown): string {
  if (!(error instanceof ApiProblem)) return `Could not start ${code}. Try again.`;
  if (problemCode(error.problem) === "run_in_progress") return `${code} is already running.`;
  return problemMessage(error.problem, `Could not start ${code}. Try again.`);
}

function Count({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium tabular-nums">{value}</dd>
    </div>
  );
}

function SourceCard({ source, onNotice }: { source: AdminSource; onNotice: (notice: Notice) => void }) {
  const setEnabled = useSetSourceEnabled();
  const startRun = useStartRun();
  const code = source.code ?? "";
  const run = source.lastRun;
  const alerts = source.alerts ?? [];
  const running = Boolean(source.running) || startRun.isPending;
  const reasonId = `${code}-reason`;

  function toggle() {
    setEnabled.mutate(
      { code, enabled: !source.enabled },
      {
        onSuccess: (updated) =>
          onNotice({
            kind: "info",
            text: updated.enabled
              ? `${code} is back on the schedule.`
              : `${code} is off the schedule. A run already in progress will finish.`,
          }),
        onError: (error) =>
          onNotice({
            kind: "error",
            text: problemMessage(error instanceof ApiProblem ? error.problem : undefined, `Could not change ${code}. Try again.`),
          }),
      },
    );
  }

  function start() {
    startRun.mutate(code, {
      onSuccess: () => onNotice({ kind: "info", text: `Run of ${code} started. This page updates when it finishes.` }),
      onError: (error) => onNotice({ kind: "error", text: runErrorMessage(code, error) }),
    });
  }

  return (
    <li className="flex flex-col gap-3 rounded-xl border bg-card p-4 text-card-foreground" aria-label={code}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-2">
          <h2 className="text-base font-semibold tracking-tight">
            {code} <span className="text-xs font-normal text-muted-foreground">{source.kind}</span>
          </h2>
          <div className="flex flex-wrap items-center gap-2">
            <HealthBadge health={source.health} />
            <Badge variant={source.schedule === "SCHEDULED" ? "secondary" : "outline"}>
              {SCHEDULE_LABELS[source.schedule ?? ""] ?? source.schedule}
            </Badge>
            {running && <Badge variant="secondary">Running now</Badge>}
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <button
              type="button"
              role="switch"
              aria-checked={Boolean(source.enabled)}
              aria-label={`Scheduled runs for ${code}`}
              disabled={setEnabled.isPending}
              onClick={toggle}
              className={cn(
                "relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border border-transparent transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50",
                source.enabled ? "bg-primary" : "bg-input",
              )}
            >
              <span
                aria-hidden="true"
                className={cn(
                  "pointer-events-none block size-5 rounded-full bg-background shadow transition-transform",
                  source.enabled ? "translate-x-5" : "translate-x-0.5",
                )}
              />
            </button>
            <span className="w-7 text-sm" aria-hidden="true">
              {source.enabled ? "On" : "Off"}
            </span>
          </div>
          <Button
            variant="outline"
            size="sm"
            aria-label={`Run now: ${code}`}
            aria-describedby={source.unavailableReason ? reasonId : undefined}
            disabled={running || Boolean(source.unavailableReason)}
            onClick={start}
          >
            <Play />
            {running ? "Running" : "Run now"}
          </Button>
        </div>
      </div>

      {source.unavailableReason && (
        <p id={reasonId} className="text-sm text-muted-foreground">
          {source.unavailableReason}
        </p>
      )}

      {alerts.length > 0 && (
        <ul className="flex flex-col gap-2" aria-label={`Open alerts for ${code}`}>
          {alerts.map((alert) => (
            <li key={alert.rule} className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
              <strong>{ALERT_LABELS[alert.rule ?? ""] ?? alert.rule}</strong> since{" "}
              <time dateTime={alert.since} title={formatDateTime(alert.since)}>
                {formatAgo(alert.since)}
              </time>{" "}
              ({alert.occurrences} {alert.occurrences === 1 ? "run" : "runs"}). {alert.detail}
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-col gap-2 text-sm">
        {run ? (
          <>
            <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span className="text-muted-foreground">Last run</span>
              <RunStatusBadge status={run.status} />
              <time dateTime={run.startedAt} title={formatDateTime(run.startedAt)}>
                {formatAgo(run.startedAt)}
              </time>
              <span className="text-muted-foreground">took {formatDuration(run.startedAt, run.finishedAt)}</span>
            </p>
            <dl className="grid grid-cols-3 gap-x-4 gap-y-2 sm:grid-cols-6">
              <Count label="Fetched" value={run.fetched ?? 0} />
              <Count label="Created" value={run.created ?? 0} />
              <Count label="Updated" value={run.updated ?? 0} />
              <Count label="Expired" value={run.expired ?? 0} />
              <Count
                label="Errors"
                value={run.targets ? `${run.errors ?? 0} of ${run.targets}` : (run.errors ?? 0)}
              />
              <Count label="Targets on" value={`${source.enabledTargets ?? 0} of ${source.totalTargets ?? 0}`} />
            </dl>
            {run.errorSummary && (
              <details className="text-sm">
                <summary className="cursor-pointer rounded-md outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                  What failed
                </summary>
                <pre className="mt-1 max-w-full overflow-x-auto rounded-lg bg-muted p-2 text-xs whitespace-pre-wrap">
                  {run.errorSummary}
                </pre>
              </details>
            )}
          </>
        ) : (
          <p className="text-muted-foreground">
            Not run yet. {source.enabledTargets ?? 0} of {source.totalTargets ?? 0} targets on.
          </p>
        )}
        <p className="text-muted-foreground">
          Next scheduled run:{" "}
          {source.schedule !== "SCHEDULED" ? (
            "none"
          ) : source.nextDueAt ? (
            <time dateTime={source.nextDueAt} title={formatDateTime(source.nextDueAt)}>
              {formatUntil(source.nextDueAt)}
            </time>
          ) : (
            "at the next check"
          )}
        </p>
      </div>
    </li>
  );
}

export function SourceDashboard() {
  const sources = useAdminSources();
  const [notice, setNotice] = useState<Notice | null>(null);
  const items = sources.data?.items ?? [];

  if (isForbidden(sources.error)) return <NotAllowed />;

  const problem = sources.error instanceof ApiProblem ? sources.error.problem : undefined;
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Job sources</h1>
        <Link href="/admin/ingestion/runs" className="rounded-md text-sm underline outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          Run history
        </Link>
      </div>

      <div role="status" aria-live="polite" className={cn("text-sm", !notice || notice.kind === "error" ? "sr-only" : "rounded-lg bg-muted px-3 py-2")}>
        {notice?.kind === "info" ? notice.text : ""}
      </div>
      {notice?.kind === "error" && <FormError>{notice.text}</FormError>}

      {sources.isPending && (
        <p role="status" className="text-sm text-muted-foreground">
          Loading sources
        </p>
      )}
      {sources.isError && (
        <>
          <FormError>{problemMessage(problem, "Could not load the sources. Try again.")}</FormError>
          <div>
            <Button variant="outline" onClick={() => void sources.refetch()}>
              Try again
            </Button>
          </div>
        </>
      )}
      {sources.isSuccess && items.length === 0 && <p className="text-sm text-muted-foreground">No sources are registered.</p>}
      {items.length > 0 && (
        <ul className="flex flex-col gap-3" aria-label="Sources">
          {items.map((source) => (
            <SourceCard key={source.code} source={source} onNotice={setNotice} />
          ))}
        </ul>
      )}
    </div>
  );
}
