"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { RunStatusBadge } from "./badges";
import { formatDateTime, formatDuration } from "./format";
import { NotAllowed, isForbidden } from "./require-admin";
import { useAdminRuns, useAdminSources } from "./queries";

const selectClass =
  "h-9 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30";

/** The URL holds the filter: `?source=GREENHOUSE&page=2` (pages count from 1 there, from 0 in the API). */
function readParams(params: URLSearchParams) {
  const source = params.get("source") || undefined;
  const page = Math.max(1, Number.parseInt(params.get("page") ?? "1", 10) || 1) - 1;
  return { source, page };
}

export function RunHistory() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { source, page } = readParams(params);

  const sources = useAdminSources();
  const runs = useAdminRuns(source, page);
  const codes = (sources.data?.items ?? []).map((s) => s.code ?? "").filter(Boolean);
  const items = runs.data?.items ?? [];
  const totalPages = runs.data?.totalPages ?? 0;
  const total = runs.data?.totalElements ?? 0;

  function go(next: { source?: string; page?: number }) {
    const query = new URLSearchParams();
    const nextSource = "source" in next ? next.source : source;
    const nextPage = next.page ?? 0;
    if (nextSource) query.set("source", nextSource);
    if (nextPage > 0) query.set("page", String(nextPage + 1));
    const text = query.toString();
    router.replace(text ? `${pathname}?${text}` : pathname);
  }

  if (isForbidden(runs.error)) return <NotAllowed />;

  const problem = runs.error instanceof ApiProblem ? runs.error.problem : undefined;
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Run history</h1>
        <Link href="/admin/ingestion" className="rounded-md text-sm underline outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          Job sources
        </Link>
      </div>

      <div className="flex max-w-xs flex-col gap-1.5">
        <Label htmlFor="run-source">Source</Label>
        <select
          id="run-source"
          className={selectClass}
          value={source ?? ""}
          onChange={(event) => go({ source: event.target.value || undefined, page: 0 })}
        >
          <option value="">All sources</option>
          {source && !codes.includes(source) && <option value={source}>{source}</option>}
          {codes.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </div>

      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {runs.isPending ? "Loading runs" : runs.isError ? "" : total === 0 ? "No runs yet" : `${total} ${total === 1 ? "run" : "runs"}, page ${page + 1} of ${Math.max(totalPages, 1)}`}
      </div>

      {runs.isError && (
        <>
          <FormError>{problemMessage(problem, "Could not load the run history. Try again.")}</FormError>
          <div>
            <Button variant="outline" onClick={() => void runs.refetch()}>
              Try again
            </Button>
          </div>
        </>
      )}

      {items.length > 0 && (
        <div
          className="overflow-x-auto rounded-xl border"
          role="region"
          aria-label="Run history table"
          tabIndex={0}
        >
          <table className="w-full min-w-[56rem] text-left text-sm">
            <caption className="sr-only">Ingestion runs, newest first</caption>
            <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">Source</th>
                <th scope="col" className="px-3 py-2 font-medium">Started</th>
                <th scope="col" className="px-3 py-2 font-medium">Took</th>
                <th scope="col" className="px-3 py-2 font-medium">Status</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Targets</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Fetched</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Created</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Updated</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Expired</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Errors</th>
              </tr>
            </thead>
            <tbody>
              {items.map((run) => (
                <tr key={run.id} className="border-b align-top last:border-b-0">
                  <th scope="row" className="px-3 py-2 font-medium">{run.source}</th>
                  <td className="px-3 py-2 whitespace-nowrap">
                    <time dateTime={run.startedAt}>{formatDateTime(run.startedAt)}</time>
                  </td>
                  <td className="px-3 py-2 whitespace-nowrap">{formatDuration(run.startedAt, run.finishedAt)}</td>
                  <td className="px-3 py-2"><RunStatusBadge status={run.status} /></td>
                  <td className="px-3 py-2 text-right tabular-nums">{run.targets ?? 0}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{run.fetched ?? 0}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{run.created ?? 0}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{run.updated ?? 0}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{run.expired ?? 0}</td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    {run.errors ?? 0}
                    {run.errorSummary && (
                      <details className="mt-1 text-left">
                        <summary className="cursor-pointer rounded-md text-xs outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                          Details
                          <span className="sr-only"> for the {run.source} run started {formatDateTime(run.startedAt)}</span>
                        </summary>
                        <pre className="mt-1 max-w-72 overflow-x-auto rounded-lg bg-muted p-2 text-xs whitespace-pre-wrap">
                          {run.errorSummary}
                        </pre>
                      </details>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <nav aria-label="Run history pages" className="flex items-center gap-2">
          <Button variant="outline" disabled={page <= 0} onClick={() => go({ page: page - 1 })}>
            Previous
          </Button>
          <Button variant="outline" disabled={page + 1 >= totalPages} onClick={() => go({ page: page + 1 })}>
            Next
          </Button>
        </nav>
      )}
    </div>
  );
}
