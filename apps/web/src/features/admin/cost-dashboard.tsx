"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { formatCount, formatUsd } from "./format";
import { NotAllowed, isForbidden } from "./require-admin";
import { useAdminCosts, type AdminCostRow } from "./queries";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The URL holds the range: `?from=2026-09-24&to=2026-10-01`. A missing or malformed date means "the default". */
function readParams(params: URLSearchParams) {
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  return { from: DATE.test(from) ? from : undefined, to: DATE.test(to) ? to : undefined };
}

function Summary({ totals }: { totals: AdminCostRow | undefined }) {
  const items = [
    { label: "Cost", value: formatUsd(totals?.costUsd) },
    { label: "Calls", value: formatCount(totals?.calls) },
    { label: "Failed calls", value: formatCount(totals?.failedCalls) },
    { label: "Input tokens", value: formatCount(totals?.inputTokens) },
    { label: "Output tokens", value: formatCount(totals?.outputTokens) },
  ];
  return (
    <dl aria-label="Totals for the range" className="grid grid-cols-2 gap-3 sm:grid-cols-5">
      {items.map(({ label, value }) => (
        <div key={label} className="rounded-xl border p-3">
          <dt className="text-xs text-muted-foreground">{label}</dt>
          <dd className="text-lg font-semibold tabular-nums">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

type Column = "day" | "feature" | "model";
const COLUMN_LABELS: Record<Column, string> = { day: "Day", feature: "Feature", model: "Model" };

/** One grouping of the report as a table. `keys` are the columns that identify a row, in order. */
function CostTable({ caption, keys, rows }: { caption: string; keys: Column[]; rows: AdminCostRow[] | undefined }) {
  if (!rows || rows.length === 0) return null;
  return (
    <section aria-label={caption} className="flex flex-col gap-2">
      <h2 className="text-lg font-semibold">{caption}</h2>
      <div className="overflow-x-auto rounded-xl border" role="region" aria-label={`${caption} table`} tabIndex={0}>
        <table className="w-full min-w-[40rem] text-left text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
            <tr>
              {keys.map((key) => (
                <th key={key} scope="col" className="px-3 py-2 font-medium">
                  {COLUMN_LABELS[key]}
                </th>
              ))}
              <th scope="col" className="px-3 py-2 text-right font-medium">Cost</th>
              <th scope="col" className="px-3 py-2 text-right font-medium">Calls</th>
              <th scope="col" className="px-3 py-2 text-right font-medium">Failed</th>
              <th scope="col" className="px-3 py-2 text-right font-medium">Input tokens</th>
              <th scope="col" className="px-3 py-2 text-right font-medium">Output tokens</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={keys.map((key) => row[key]).join("|")} className="border-b last:border-b-0">
                {keys.map((key, index) =>
                  index === 0 ? (
                    <th key={key} scope="row" className="px-3 py-2 font-medium whitespace-nowrap">
                      {row[key]}
                    </th>
                  ) : (
                    <td key={key} className="px-3 py-2 whitespace-nowrap">
                      {row[key]}
                    </td>
                  ),
                )}
                <td className="px-3 py-2 text-right tabular-nums">{formatUsd(row.costUsd)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatCount(row.calls)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatCount(row.failedCalls)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatCount(row.inputTokens)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatCount(row.outputTokens)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export function CostDashboard() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { from, to } = readParams(params);
  const costs = useAdminCosts(from, to);
  const report = costs.data;

  function go(next: { from?: string; to?: string }) {
    const query = new URLSearchParams();
    const nextFrom = "from" in next ? next.from : from;
    const nextTo = "to" in next ? next.to : to;
    if (nextFrom) query.set("from", nextFrom);
    if (nextTo) query.set("to", nextTo);
    const text = query.toString();
    router.replace(text ? `${pathname}?${text}` : pathname);
  }

  if (isForbidden(costs.error)) return <NotAllowed />;

  const problem = costs.error instanceof ApiProblem ? costs.error.problem : undefined;
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">AI cost</h1>
        <Link href="/admin/ingestion" className="rounded-md text-sm underline outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          Job sources
        </Link>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="cost-from">From</Label>
          <Input
            id="cost-from"
            type="date"
            value={from ?? report?.from ?? ""}
            max={to ?? report?.to}
            onChange={(event) => go({ from: event.target.value || undefined })}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="cost-to">To</Label>
          <Input
            id="cost-to"
            type="date"
            value={to ?? report?.to ?? ""}
            min={from ?? report?.from}
            onChange={(event) => go({ to: event.target.value || undefined })}
          />
        </div>
        {(from || to) && (
          <Button variant="outline" onClick={() => router.replace(pathname)}>
            Last 7 days
          </Button>
        )}
      </div>
      <p className="text-xs text-muted-foreground">Days are UTC days; both ends are included. Calls the provider billed but whose output was discarded count as failed and are included in the cost.</p>

      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {costs.isPending ? "Loading AI cost" : costs.isError ? "" : report?.totals?.calls === 0 ? "No AI calls in this range" : `${formatCount(report?.totals?.calls)} calls from ${report?.from} to ${report?.to}`}
      </div>

      {costs.isError && (
        <>
          <FormError>{problemMessage(problem, "Could not load the AI cost. Try again.")}</FormError>
          <div>
            <Button variant="outline" onClick={() => void costs.refetch()}>
              Try again
            </Button>
          </div>
        </>
      )}

      {report && (report.totals?.calls ?? 0) > 0 && (
        <>
          <Summary totals={report.totals} />
          <CostTable caption="Cost by day" keys={["day"]} rows={report.byDay} />
          <CostTable caption="Cost by feature" keys={["feature"]} rows={report.byFeature} />
          <CostTable caption="Cost by model" keys={["model"]} rows={report.byModel} />
          <CostTable caption="Cost by feature per day" keys={["day", "feature"]} rows={report.byDayFeature} />
        </>
      )}
    </div>
  );
}
