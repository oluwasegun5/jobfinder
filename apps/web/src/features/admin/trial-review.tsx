"use client";

import { X } from "lucide-react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import type { KeyboardEvent } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { EmptyState, LoadingState } from "@/components/state";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";
import { ApiProblem } from "@/features/profile/queries";

import { formatCount, formatDateTime } from "./format";
import { NotAllowed, isForbidden } from "./require-admin";
import {
  TRIAL_NOTE_MAX,
  useAdminTrial,
  useAdminTrials,
  useDecideTrial,
  type AdminTrialItem,
  type TrialStateFilter,
  type TrialView,
} from "./queries";

/** The gate's rule and signal codes (docs/adr/0042-trial-credit-abuse-gate.md) in words. The API sends only the code. */
export const REASON_LABELS: Record<string, string> = {
  "account-email": "Account email was already used for a trial",
  "google-account": "Google account was already used for a trial",
  "disposable-domain": "Disposable email domain",
  "cv-file": "CV file is the exact file of another account",
  "cv-file-shared": "CV file is on several accounts (a template?)",
  "cv-email": "Email address in the CV matches another account",
  "cv-email-is-account-email": "CV email is the login email",
  "cv-phone": "Phone number in the CV matches another account",
  "google-sign-in": "Signed in with Google",
  "fast-onboarding": "Milestones reached unusually fast",
  "hourly-ceiling": "Circuit breaker: many trials granted in the last hour",
  "no-claim-key": "Email address could not be read for the check",
};

export const STATE_LABELS: Record<string, string> = { HOLD: "On hold", DENIED: "Denied", GRANTED: "Granted" };
export const REVIEW_LABELS: Record<string, string> = {
  PENDING: "Waiting for review",
  APPROVED: "Approved by an admin",
  DENIED: "Denied by an admin",
};

const reasonLabel = (code: string | undefined) => REASON_LABELS[code ?? ""] ?? code ?? "";

const VIEWS: TrialView[] = ["PENDING", "DECIDED"];
const STATES: TrialStateFilter[] = ["HOLD", "DENIED", "GRANTED"];

const selectClass =
  "h-9 rounded-lg border border-input bg-card px-3 text-sm text-foreground shadow-xs outline-none focus-visible:border-ring focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring";

function StateBadge({ state }: { state: string | undefined }) {
  const label = STATE_LABELS[state ?? ""] ?? state ?? "";
  if (state === "DENIED") return <Badge variant="destructive">{label}</Badge>;
  if (state === "HOLD") return <Badge variant="warning">{label}</Badge>;
  return <Badge variant="success">{label}</Badge>;
}

function shortDate(iso: string | undefined): string {
  if (!iso) return "-";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleDateString("en", { dateStyle: "medium" });
}

/** The URL holds the filters and the page (one-based): `?view=DECIDED&state=DENIED&page=2`. Anything else is the default. */
function readParams(params: URLSearchParams): { view: TrialView; state?: TrialStateFilter; page: number } {
  const view = VIEWS.find((v) => v === params.get("view")) ?? "PENDING";
  const state = STATES.find((s) => s === params.get("state"));
  const page = Number.parseInt(params.get("page") ?? "1", 10);
  return { view, state, page: Number.isFinite(page) && page >= 1 ? page : 1 };
}

export function TrialReviewQueue() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { view, state, page } = readParams(params);
  const trials = useAdminTrials(view, state, page - 1);
  const [openUserId, setOpenUserId] = useState<string>();
  const opener = useRef<HTMLElement | null>(null);
  const viewId = useId();
  const stateId = useId();

  function go(next: { view?: TrialView; state?: TrialStateFilter | ""; page?: number }) {
    const query = new URLSearchParams();
    const nextView = next.view ?? view;
    const nextState = "state" in next ? next.state || undefined : state;
    const nextPage = next.page ?? 1;
    if (nextView !== "PENDING") query.set("view", nextView);
    if (nextState) query.set("state", nextState);
    if (nextPage > 1) query.set("page", String(nextPage));
    const text = query.toString();
    router.replace(text ? `${pathname}?${text}` : pathname);
  }

  function open(userId: string | undefined, button: HTMLElement) {
    opener.current = button;
    setOpenUserId(userId);
  }

  function close() {
    setOpenUserId(undefined);
    opener.current?.focus();
  }

  if (isForbidden(trials.error)) return <NotAllowed />;

  const problem = trials.error instanceof ApiProblem ? trials.error.problem : undefined;
  const items = trials.data?.items ?? [];
  const total = trials.data?.totalElements ?? 0;
  const totalPages = Math.max(trials.data?.totalPages ?? 1, 1);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-title font-semibold">Trial review</h1>
      <p className="max-w-prose text-sm text-muted-foreground">
        Accounts the free-trial check held or denied, oldest first. Approve to release the trial credits the person
        earned, or deny to make the denial final. Emails are masked; every decision is recorded with your note.
      </p>

      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={viewId}>Show</Label>
          <select
            id={viewId}
            className={selectClass}
            value={view}
            onChange={(event) => go({ view: event.target.value as TrialView })}
          >
            <option value="PENDING">Waiting for review</option>
            <option value="DECIDED">Already decided</option>
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={stateId}>State</Label>
          <select
            id={stateId}
            className={selectClass}
            value={state ?? ""}
            onChange={(event) => go({ state: event.target.value as TrialStateFilter | "" })}
          >
            <option value="">Any state</option>
            <option value="HOLD">On hold</option>
            <option value="DENIED">Denied</option>
            {view === "DECIDED" && <option value="GRANTED">Granted</option>}
          </select>
        </div>
      </div>

      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {trials.isPending
          ? "Loading trial review queue"
          : trials.isError
            ? ""
            : total === 0
              ? "No accounts to show"
              : `${formatCount(total)} ${total === 1 ? "account" : "accounts"}`}
      </div>

      {trials.isError && (
        <>
          <FormError>{problemMessage(problem, "Could not load the trial review queue. Try again.")}</FormError>
          <div>
            <Button variant="outline" onClick={() => void trials.refetch()}>
              Try again
            </Button>
          </div>
        </>
      )}

      {trials.isPending && <LoadingState>Loading trial review queue</LoadingState>}

      {trials.data && items.length === 0 && (
        <EmptyState title={view === "PENDING" ? "Nothing waiting for review" : "No decisions yet"}>
          {view === "PENDING"
            ? "No account is on hold or denied right now."
            : "Accounts you approve or deny will be listed here."}
        </EmptyState>
      )}

      {items.length > 0 && (
        <div className="overflow-x-auto rounded-xl border" role="region" aria-label="Trial accounts" tabIndex={0}>
          <table className="w-full min-w-[40rem] text-left text-sm">
            <caption className="sr-only">Trial accounts held or denied by the free-trial check, oldest first</caption>
            <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">Account</th>
                <th scope="col" className="px-3 py-2 font-medium">Signed up</th>
                <th scope="col" className="px-3 py-2 font-medium">State</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Score</th>
                <th scope="col" className="px-3 py-2 font-medium">Why</th>
                <th scope="col" className="px-3 py-2 font-medium">
                  <span className="sr-only">Review</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <TrialRow key={item.userId} item={item} onReview={open} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {trials.data && totalPages > 1 && (
        <nav aria-label="Pages" className="flex items-center gap-3">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => go({ page: page - 1 })}>
            Previous
          </Button>
          <span className="text-sm text-muted-foreground tabular-nums">
            Page {page} of {totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => go({ page: page + 1 })}>
            Next
          </Button>
        </nav>
      )}

      {openUserId && <TrialDrawer userId={openUserId} onClose={close} />}
    </div>
  );
}

function TrialRow({ item, onReview }: { item: AdminTrialItem; onReview: (userId: string | undefined, button: HTMLElement) => void }) {
  const reasons = item.reasons ?? [];
  return (
    <tr className="border-b last:border-b-0">
      <th scope="row" className="px-3 py-2 font-medium whitespace-nowrap">{item.maskedEmail}</th>
      <td className="px-3 py-2 whitespace-nowrap">{shortDate(item.signedUpAt)}</td>
      <td className="px-3 py-2">
        <StateBadge state={item.state} />
      </td>
      <td className="px-3 py-2 text-right tabular-nums">{item.riskScore}</td>
      <td className="px-3 py-2">
        {reasons.length === 0 ? (
          <span className="text-muted-foreground">Not recorded</span>
        ) : (
          <>
            {reasonLabel(reasons[0].code)}
            {reasons.length > 1 && <span className="text-muted-foreground"> and {reasons.length - 1} more</span>}
          </>
        )}
      </td>
      <td className="px-3 py-2 text-right">
        <Button
          variant="outline"
          size="sm"
          aria-label={`Review ${item.maskedEmail}`}
          onClick={(event) => onReview(item.userId, event.currentTarget)}
        >
          Review
        </Button>
      </td>
    </tr>
  );
}

const FOCUSABLE = 'button:not([disabled]), textarea:not([disabled]), [href], select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The detail of one account, in a drawer: why the gate decided as it did, the decisions so far, and the two actions.
 * A modal dialog: focus moves in, stays in, Escape or the close button leaves, and the page behind is inert to the
 * pointer through the backdrop.
 */
function TrialDrawer({ userId, onClose }: { userId: string; onClose: () => void }) {
  const detail = useAdminTrial(userId);
  const decide = useDecideTrial();
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const noteId = useId();
  const noteHelpId = useId();
  const [action, setAction] = useState<"approve" | "deny">();
  const [note, setNote] = useState("");
  const [done, setDone] = useState<string>();

  useEffect(() => {
    panel.current?.focus();
  }, []);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab" || !panel.current) return;
    const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  const item = detail.data?.item;
  const decisions = detail.data?.decisions ?? [];
  const trimmed = note.trim();
  const decideProblem = decide.error instanceof ApiProblem ? decide.error.problem : undefined;
  const detailProblem = detail.error instanceof ApiProblem ? detail.error.problem : undefined;
  const pending = item?.reviewStatus === "PENDING" && (item.state === "HOLD" || item.state === "DENIED");
  const unreleased = (item?.milestonesReached ?? []).filter((code) => !(item?.milestonesGranted ?? []).includes(code));

  function confirm() {
    if (!action || trimmed.length === 0) return;
    decide.mutate(
      { userId, action, note: trimmed },
      {
        onSuccess: (outcome) => {
          const credits = Number(outcome.creditsGranted ?? 0);
          setDone(
            !outcome.applied
              ? "This decision had already been taken. Nothing changed."
              : action === "approve"
                ? `Approved. ${formatCount(credits)} trial ${credits === 1 ? "credit" : "credits"} released.`
                : "Denied. This is final.",
          );
          setAction(undefined);
          setNote("");
        },
      },
    );
  }

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-foreground/40" aria-hidden onClick={onClose} />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className="relative flex h-full w-full max-w-md flex-col gap-4 overflow-y-auto border-l bg-background p-5 text-foreground shadow-lg outline-none"
      >
        <div className="flex items-start justify-between gap-3">
          <h2 id={titleId} className="text-lead font-semibold break-all">
            {item?.maskedEmail ?? "Trial account"}
          </h2>
          <Button variant="ghost" size="icon-sm" aria-label="Close" onClick={onClose}>
            <X aria-hidden />
          </Button>
        </div>

        {detail.isPending && <LoadingState>Loading account</LoadingState>}
        {detail.isError && (
          <>
            <FormError>{problemMessage(detailProblem, "Could not load this account. Try again.")}</FormError>
            <div>
              <Button variant="outline" onClick={() => void detail.refetch()}>
                Try again
              </Button>
            </div>
          </>
        )}

        {item && (
          <>
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
              <dt className="text-muted-foreground">Account id</dt>
              <dd className="font-mono text-xs break-all">{item.userId}</dd>
              <dt className="text-muted-foreground">Signed up</dt>
              <dd>{formatDateTime(item.signedUpAt)}</dd>
              <dt className="text-muted-foreground">State</dt>
              <dd>
                <StateBadge state={item.state} />
              </dd>
              <dt className="text-muted-foreground">Review</dt>
              <dd>{REVIEW_LABELS[item.reviewStatus ?? ""] ?? item.reviewStatus}</dd>
              <dt className="text-muted-foreground">Risk score</dt>
              <dd className="tabular-nums">{item.riskScore}</dd>
              <dt className="text-muted-foreground">Milestones reached</dt>
              <dd>{(item.milestonesReached ?? []).join(", ") || "None"}</dd>
              <dt className="text-muted-foreground">Credits released for</dt>
              <dd>{(item.milestonesGranted ?? []).join(", ") || "None"}</dd>
              {item.trialExpiresAt && (
                <>
                  <dt className="text-muted-foreground">Trial ends</dt>
                  <dd>{formatDateTime(item.trialExpiresAt)}</dd>
                </>
              )}
            </dl>

            <section aria-labelledby={`${titleId}-reasons`} className="flex flex-col gap-2">
              <h3 id={`${titleId}-reasons`} className="text-sm font-semibold">Why the check decided this</h3>
              {(item.reasons ?? []).length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  No reason was recorded. This account was decided before reasons were kept.
                </p>
              ) : (
                <ul className="flex flex-col gap-1.5 text-sm">
                  {(item.reasons ?? []).map((reason) => (
                    <li key={reason.code} className="flex items-start justify-between gap-3 rounded-lg border px-3 py-2">
                      <span>{reasonLabel(reason.code)}</span>
                      <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
                        {reason.hard ? "Hard rule" : `${(reason.points ?? 0) > 0 ? "+" : ""}${reason.points ?? 0} points`}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section aria-labelledby={`${titleId}-history`} className="flex flex-col gap-2">
              <h3 id={`${titleId}-history`} className="text-sm font-semibold">Decisions</h3>
              {decisions.length === 0 ? (
                <p className="text-sm text-muted-foreground">No admin has decided on this account yet.</p>
              ) : (
                <ul className="flex flex-col gap-2 text-sm">
                  {decisions.map((d) => (
                    <li key={`${d.decidedAt}-${d.adminId}-${d.action}`} className="rounded-lg border px-3 py-2">
                      <p className="font-medium">
                        {d.action === "APPROVE" ? "Approved" : "Denied"} on {formatDateTime(d.decidedAt)}
                      </p>
                      <p className="text-xs text-muted-foreground break-all">By admin {d.adminId}</p>
                      <p className="mt-1 whitespace-pre-wrap">{d.note}</p>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {done && (
              <p role="status" className="rounded-lg bg-muted px-3 py-2 text-sm">
                {done}
              </p>
            )}

            {pending && !action && (
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => { setDone(undefined); decide.reset(); setAction("approve"); }}>Approve</Button>
                <Button variant="destructive" onClick={() => { setDone(undefined); decide.reset(); setAction("deny"); }}>
                  Deny
                </Button>
              </div>
            )}

            {pending && action && (
              <form
                role="group"
                aria-labelledby={`${titleId}-confirm`}
                className="flex flex-col gap-3 rounded-xl border p-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  confirm();
                }}
              >
                <h3 id={`${titleId}-confirm`} className="text-sm font-semibold">
                  {action === "approve" ? "Approve this trial?" : "Deny this trial for good?"}
                </h3>
                <p className="text-sm text-muted-foreground">
                  {action === "approve"
                    ? unreleased.length > 0
                      ? `This releases the trial credits for ${unreleased.join(", ")}. It is safe to repeat.`
                      : "The trial credits earned so far are already released. This lifts the hold."
                    : "The person keeps their account but gets no trial. Unspent trial credits expire. You cannot approve this account afterwards."}
                </p>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor={noteId}>Note (required)</Label>
                  <Textarea
                    id={noteId}
                    value={note}
                    maxLength={TRIAL_NOTE_MAX}
                    required
                    aria-describedby={noteHelpId}
                    onChange={(event) => setNote(event.target.value)}
                  />
                  <p id={noteHelpId} className="text-xs text-muted-foreground">
                    Why you decided this. Kept for 24 months. No personal details, please. {note.length}/{TRIAL_NOTE_MAX}
                  </p>
                </div>
                {decide.isError && (
                  <FormError>{problemMessage(decideProblem, "Could not save the decision. Try again.")}</FormError>
                )}
                <div className="flex flex-wrap gap-2">
                  <Button
                    type="submit"
                    variant={action === "approve" ? "default" : "destructive"}
                    disabled={trimmed.length === 0 || decide.isPending}
                  >
                    {action === "approve" ? "Confirm approve" : "Confirm deny"}
                  </Button>
                  <Button type="button" variant="outline" disabled={decide.isPending} onClick={() => { setAction(undefined); decide.reset(); }}>
                    Back
                  </Button>
                </div>
              </form>
            )}
          </>
        )}
      </div>
    </div>
  );
}
