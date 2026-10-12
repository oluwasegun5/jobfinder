"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { formatCount } from "./format";
import { NotAllowed, isForbidden } from "./require-admin";
import { useAdminActivation } from "./queries";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The funnel's steps, in the order the API returns them. The API sends the codes; the words are the web app's. */
export const STEP_LABELS: Record<string, string> = {
  SIGNUP: "Signed up",
  EMAIL_VERIFIED: "Email verified",
  CV_UPLOADED: "CV uploaded",
  CV_PARSED: "CV parsed",
  ONBOARDING_COMPLETED: "Preferences complete",
  FIRST_MATCHES_VIEWED: "First matches viewed",
  FIRST_TAILORED_CV: "First tailored CV",
};

const label = (step: string | undefined) => STEP_LABELS[step ?? ""] ?? step ?? "";

/** "62.5%" for a ratio of 0.625; a dash when there was nothing to divide by. */
export function formatRate(ratio: number | undefined | null): string {
  if (ratio === undefined || ratio === null) return "–";
  return `${(Math.round(ratio * 1000) / 10).toLocaleString("en", { maximumFractionDigits: 1 })}%`;
}

/** The URL holds the range: `?from=2026-09-24&to=2026-10-01`. A missing or malformed date means "the default". */
function readParams(params: URLSearchParams) {
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  return { from: DATE.test(from) ? from : undefined, to: DATE.test(to) ? to : undefined };
}

export function ActivationDashboard() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { from, to } = readParams(params);
  const activation = useAdminActivation(from, to);
  const report = activation.data;

  function go(next: { from?: string; to?: string }) {
    const query = new URLSearchParams();
    const nextFrom = "from" in next ? next.from : from;
    const nextTo = "to" in next ? next.to : to;
    if (nextFrom) query.set("from", nextFrom);
    if (nextTo) query.set("to", nextTo);
    const text = query.toString();
    router.replace(text ? `${pathname}?${text}` : pathname);
  }

  if (isForbidden(activation.error)) return <NotAllowed />;

  const problem = activation.error instanceof ApiProblem ? activation.error.problem : undefined;
  const steps = report?.steps ?? [];
  const signups = steps[0]?.count ?? 0;
  const trial = report?.trial;

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-title font-semibold">Activation</h1>

      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="activation-from">From</Label>
          <Input
            id="activation-from"
            type="date"
            value={from ?? report?.from ?? ""}
            max={to ?? report?.to}
            onChange={(event) => go({ from: event.target.value || undefined })}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="activation-to">To</Label>
          <Input
            id="activation-to"
            type="date"
            value={to ?? report?.to ?? ""}
            min={from ?? report?.from}
            onChange={(event) => go({ to: event.target.value || undefined })}
          />
        </div>
        {(from || to) && (
          <Button variant="outline" onClick={() => router.replace(pathname)}>
            Last 30 days
          </Button>
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        People who signed up in the range (UTC days, both ends included), by the furthest step they reached in order. A
        person counts at a step only if they also reached every step before it. Counts only: nobody is identified.
      </p>

      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {activation.isPending
          ? "Loading activation"
          : activation.isError
            ? ""
            : signups === 0
              ? "No signups in this range"
              : `${formatCount(signups)} signups from ${report?.from} to ${report?.to}`}
      </div>

      {activation.isError && (
        <>
          <FormError>{problemMessage(problem, "Could not load the activation funnel. Try again.")}</FormError>
          <div>
            <Button variant="outline" onClick={() => void activation.refetch()}>
              Try again
            </Button>
          </div>
        </>
      )}

      {report && signups > 0 && (
        <>
          <section aria-label="Funnel" className="flex flex-col gap-2">
            <h2 className="text-lead font-semibold">Funnel</h2>
            <div className="overflow-x-auto rounded-xl border" role="region" aria-label="Funnel table" tabIndex={0}>
              <table className="w-full min-w-[32rem] text-left text-sm">
                <caption className="sr-only">Users per activation step with conversion</caption>
                <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-medium">Step</th>
                    <th scope="col" className="px-3 py-2 text-right font-medium">Users</th>
                    <th scope="col" className="px-3 py-2 text-right font-medium">From previous step</th>
                    <th scope="col" className="px-3 py-2 text-right font-medium">Overall</th>
                    <th scope="col" className="w-1/4 px-3 py-2 font-medium">
                      <span className="sr-only">Share of signups</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {steps.map((step) => (
                    <tr key={step.step} className="border-b last:border-b-0">
                      <th scope="row" className="px-3 py-2 font-medium whitespace-nowrap">{label(step.step)}</th>
                      <td className="px-3 py-2 text-right tabular-nums">{formatCount(step.count)}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{formatRate(step.fromPrevious)}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{formatRate(step.overall)}</td>
                      <td className="px-3 py-2" aria-hidden>
                        <div className="h-2 w-full rounded-full bg-muted">
                          <div className="h-2 rounded-full bg-primary" style={{ width: `${Math.round((step.overall ?? 0) * 100)}%` }} />
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section aria-label="Trial outcomes" className="flex flex-col gap-2">
            <h2 className="text-lead font-semibold">Trial outcomes</h2>
            <dl aria-label="Trial outcome counts" className="grid grid-cols-3 gap-3">
              {[
                { name: "Granted", value: trial?.granted },
                { name: "On hold", value: trial?.hold },
                { name: "Denied", value: trial?.denied },
              ].map(({ name, value }) => (
                <div key={name} className="rounded-xl border p-3">
                  <dt className="text-xs text-muted-foreground">{name}</dt>
                  <dd className="text-lead font-semibold tabular-nums">{formatCount(value)}</dd>
                </div>
              ))}
            </dl>
            <p className="text-xs text-muted-foreground">Accounts from before the trial are not counted.</p>
          </section>

          <section aria-label="Signup weeks" className="flex flex-col gap-2">
            <h2 className="text-lead font-semibold">By signup week</h2>
            <div className="overflow-x-auto rounded-xl border" role="region" aria-label="Signup weeks table" tabIndex={0}>
              <table className="w-full min-w-[44rem] text-left text-sm">
                <caption className="sr-only">Users per activation step, by the week they signed up</caption>
                <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-medium">Week starting</th>
                    {steps.map((step) => (
                      <th key={step.step} scope="col" className="px-3 py-2 text-right font-medium">
                        {label(step.step)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {report.cohorts?.map((cohort) => (
                    <tr key={cohort.weekStart} className="border-b last:border-b-0">
                      <th scope="row" className="px-3 py-2 font-medium whitespace-nowrap">{cohort.weekStart}</th>
                      {cohort.counts?.map((count, index) => (
                        <td key={steps[index]?.step ?? index} className="px-3 py-2 text-right tabular-nums">
                          {formatCount(count)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </div>
  );
}
