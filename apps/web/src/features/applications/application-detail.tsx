"use client";

import { ArrowLeft, ExternalLink } from "lucide-react";
import Link from "next/link";
import { useId, useState } from "react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { FormError } from "@/features/auth/form-parts";
import { safeHref } from "@/features/jobs/format";
import { formatWhen, toFailure, type Failure } from "@/features/tailoring/errors";
import { LENGTH_LABELS, TONE_LABELS, type Length, type Tone } from "@/features/tailoring/model";
import { CopyButton, FailureNotice, Section } from "@/features/tailoring/shared";

import {
  CANCEL_REASON_LABELS,
  REMINDER_KIND_LABELS,
  STATUSES,
  STATUS_LABELS,
  isStatus,
  type ApplicationDetail,
  type FollowUpDraft,
  type ReminderKind,
  type Status,
} from "./model";
import {
  useAddReminder,
  useApplication,
  useCancelReminder,
  useFollowUpDraft,
  useMoveApplication,
  useUpdateApplication,
} from "./queries";

const selectClass =
  "h-8 rounded-lg border border-input bg-background px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";
const inputClass =
  "h-8 rounded-lg border border-input bg-transparent px-2.5 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm";

function StatusControl({ app }: { app: ApplicationDetail }) {
  const move = useMoveApplication();
  const [to, setTo] = useState<Status>(app.status as Status);
  const [note, setNote] = useState("");
  const [failure, setFailure] = useState<Failure>();
  const id = useId();
  return (
    <form
      className="flex flex-wrap items-end gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        if (!app.id || to === app.status) return;
        setFailure(undefined);
        move.mutate(
          { id: app.id, to, note: note.trim() || undefined },
          {
            onSuccess: () => setNote(""),
            onError: (err) => {
              setTo(app.status as Status);
              setFailure(toFailure(err, "Could not change the status."));
            },
          },
        );
      }}
    >
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${id}-status`}>Status</Label>
        <select id={`${id}-status`} className={selectClass} value={to} onChange={(e) => isStatus(e.target.value) && setTo(e.target.value)}>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABELS[s]}
            </option>
          ))}
        </select>
      </div>
      <div className="flex min-w-48 flex-1 flex-col gap-1.5">
        <Label htmlFor={`${id}-note`}>Note for the history (optional)</Label>
        <input id={`${id}-note`} className={inputClass} value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />
      </div>
      <Button type="submit" disabled={move.isPending || to === app.status}>
        {move.isPending ? "Saving" : "Update status"}
      </Button>
      <div className="w-full">
        <FailureNotice failure={failure} />
      </div>
    </form>
  );
}

function NotesForm({ app }: { app: ApplicationDetail }) {
  const update = useUpdateApplication(app.id ?? "");
  const [notes, setNotes] = useState(app.notes ?? "");
  const [failure, setFailure] = useState<Failure>();
  const [saved, setSaved] = useState(false);
  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        setFailure(undefined);
        setSaved(false);
        update.mutate({ notes }, { onSuccess: () => setSaved(true), onError: (err) => setFailure(toFailure(err, "Could not save your notes.")) });
      }}
    >
      <Label htmlFor="app-notes">Notes (private: they are never sent to the AI)</Label>
      <Textarea id="app-notes" rows={5} maxLength={10000} value={notes} onChange={(e) => { setNotes(e.target.value); setSaved(false); }} />
      <FailureNotice failure={failure} />
      <div className="flex items-center gap-3">
        <Button type="submit" disabled={update.isPending || notes === (app.notes ?? "")}>
          {update.isPending ? "Saving" : "Save notes"}
        </Button>
        <span role="status" className="text-sm text-muted-foreground">{saved ? "Saved" : ""}</span>
      </div>
    </form>
  );
}

function Reminders({ app }: { app: ApplicationDetail }) {
  const id = app.id ?? "";
  const add = useAddReminder(id);
  const cancel = useCancelReminder(id);
  const [kind, setKind] = useState<ReminderKind>("FOLLOW_UP");
  const [due, setDue] = useState("");
  const [note, setNote] = useState("");
  const [failure, setFailure] = useState<Failure>();
  const closed = app.status === "REJECTED" || app.status === "WITHDRAWN";
  const reminders = app.reminders ?? [];

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const when = new Date(due);
    if (!due || Number.isNaN(when.getTime())) return setFailure({ kind: "validation", message: "Choose when to be reminded.", retryable: false });
    if (when.getTime() <= Date.now()) return setFailure({ kind: "validation", message: "The reminder must be in the future.", retryable: false });
    setFailure(undefined);
    add.mutate(
      { dueAt: when.toISOString(), kind, note: note.trim() || undefined },
      {
        onSuccess: () => {
          setDue("");
          setNote("");
        },
        onError: (err) => setFailure(toFailure(err, "Could not add the reminder.")),
      },
    );
  };

  return (
    <div className="flex flex-col gap-3">
      {reminders.length === 0 ? (
        <p className="text-sm text-muted-foreground">No reminders. You are only emailed about the ones you add.</p>
      ) : (
        <ul className="flex flex-col gap-2" aria-label="Reminders">
          {reminders.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3 text-sm">
              <span className="flex flex-col">
                <span className="font-medium">
                  {r.kind ? REMINDER_KIND_LABELS[r.kind] : "Reminder"} · {formatWhen(r.dueAt)}
                </span>
                {r.note && <span className="text-muted-foreground">{r.note}</span>}
                <span className="text-xs text-muted-foreground">
                  {r.state === "PENDING" ? "Pending" : r.state === "SENT" ? `Sent ${formatWhen(r.sentAt)}` : `Cancelled${r.cancelReason ? `: ${CANCEL_REASON_LABELS[r.cancelReason]}` : ""}`}
                </span>
              </span>
              {r.state === "PENDING" && r.id && (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={cancel.isPending}
                  aria-label={`Cancel reminder on ${formatWhen(r.dueAt)}`}
                  onClick={() => {
                    setFailure(undefined);
                    cancel.mutate(r.id as string, { onError: (err) => setFailure(toFailure(err, "Could not cancel the reminder.")) });
                  }}
                >
                  Cancel
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {closed ? (
        <p className="text-sm text-muted-foreground">This application is closed, so reminders cannot be added.</p>
      ) : (
        <form onSubmit={submit} aria-label="Add a reminder" className="flex flex-wrap items-end gap-3" noValidate>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="rem-kind">Kind</Label>
            <select id="rem-kind" className={selectClass} value={kind} onChange={(e) => setKind(e.target.value as ReminderKind)}>
              {(Object.keys(REMINDER_KIND_LABELS) as ReminderKind[]).map((k) => (
                <option key={k} value={k}>
                  {REMINDER_KIND_LABELS[k]}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="rem-due">When</Label>
            <input id="rem-due" type="datetime-local" className={inputClass} value={due} onChange={(e) => setDue(e.target.value)} />
          </div>
          <div className="flex min-w-40 flex-1 flex-col gap-1.5">
            <Label htmlFor="rem-note">Note (optional)</Label>
            <input id="rem-note" className={inputClass} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
          </div>
          <Button type="submit" disabled={add.isPending}>
            {add.isPending ? "Adding" : "Add reminder"}
          </Button>
        </form>
      )}
      <FailureNotice failure={failure} />
    </div>
  );
}

function FollowUp({ app }: { app: ApplicationDetail }) {
  const draft = useFollowUpDraft(app.id ?? "");
  const [tone, setTone] = useState<Tone>("FORMAL");
  const [length, setLength] = useState<Length>("SHORT");
  const [notes, setNotes] = useState("");
  const [result, setResult] = useState<FollowUpDraft>();
  const [failure, setFailure] = useState<Failure>();

  const run = () => {
    setFailure(undefined);
    draft.mutate(
      { tone, length, notes: notes.trim() || undefined },
      { onSuccess: setResult, onError: (e) => setFailure(toFailure(e, "Could not write the draft. Try again.")) },
    );
  };

  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">
        The AI writes a draft from your CV and this application. It is a draft only: nothing is sent, and you copy it into your own email.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="fu-tone">Tone</Label>
          <select id="fu-tone" className={selectClass} value={tone} onChange={(e) => setTone(e.target.value as Tone)}>
            {(Object.keys(TONE_LABELS) as Tone[]).map((t) => (
              <option key={t} value={t}>{TONE_LABELS[t]}</option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="fu-length">Length</Label>
          <select id="fu-length" className={selectClass} value={length} onChange={(e) => setLength(e.target.value as Length)}>
            {(Object.keys(LENGTH_LABELS) as Length[]).map((l) => (
              <option key={l} value={l}>{LENGTH_LABELS[l]}</option>
            ))}
          </select>
        </div>
        <div className="flex min-w-48 flex-1 flex-col gap-1.5">
          <Label htmlFor="fu-notes">Anything to mention (optional)</Label>
          <input id="fu-notes" className={inputClass} value={notes} maxLength={1000} onChange={(e) => setNotes(e.target.value)} />
        </div>
        <Button onClick={run} disabled={draft.isPending}>
          {draft.isPending ? "Writing" : result ? "Write another draft" : "Write a follow-up draft"}
        </Button>
      </div>
      <FailureNotice failure={failure} onRetry={run} retrying={draft.isPending} />
      {result && (
        <section aria-label="Follow-up draft" className="flex flex-col gap-3 rounded-lg border p-3">
          <p role="note" className="text-sm font-medium">This is a draft. Nothing has been sent. Read it, change what you need, and send it yourself.</p>
          <div className="flex flex-col gap-1">
            <h4 className="text-sm font-medium">Subject</h4>
            <p className="text-sm">{result.subject}</p>
            <CopyButton text={result.subject ?? ""} label="subject" />
          </div>
          <div className="flex flex-col gap-1">
            <h4 className="text-sm font-medium">Body</h4>
            <p className="whitespace-pre-line text-sm">{result.body}</p>
            <CopyButton text={result.body ?? ""} label="email body" />
          </div>
        </section>
      )}
    </div>
  );
}

function Detail({ app }: { app: ApplicationDetail }) {
  const href = safeHref(app.url);
  const events = app.events ?? [];
  const attached = [
    app.resumeDocumentId && "tailored CV",
    app.coverLetterDocumentId && "cover letter",
    app.screeningAnswersDocumentId && "screening answers",
  ].filter(Boolean);
  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-2">
        <Link href="/applications" className="inline-flex items-center gap-1 text-sm underline">
          <ArrowLeft className="size-4" aria-hidden /> Back to the board
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight">{app.title}</h1>
        {app.company && <p className="text-muted-foreground">{app.company}</p>}
        <div className="flex flex-wrap items-center gap-3 text-sm">
          {href && (
            <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="inline-flex items-center gap-1 underline">
              Posting <ExternalLink className="size-4" aria-hidden />
              <span className="sr-only">(opens in a new tab)</span>
            </a>
          )}
          {app.jobId && <Link href={`/jobs/${app.jobId}`} className="underline">View the job</Link>}
          {app.appliedAt && <span className="text-muted-foreground">Applied {formatWhen(app.appliedAt)}</span>}
          {attached.length > 0 && <span className="text-muted-foreground">Sent with: {attached.join(", ")}</span>}
        </div>
      </header>

      <Section id="status" title="Status">
        <StatusControl key={`${app.id}:${app.status}`} app={app} />
      </Section>

      <Section id="notes" title="Notes">
        <NotesForm key={`${app.id}:${app.updatedAt}`} app={app} />
      </Section>

      <Section id="history" title="History">
        {events.length === 0 ? (
          <p className="text-sm text-muted-foreground">No history yet.</p>
        ) : (
          <ol className="flex flex-col gap-2" aria-label="Status history">
            {events.map((event) => (
              <li key={event.id} className="rounded-lg border p-3 text-sm">
                <span className="font-medium">
                  {event.from ? `${STATUS_LABELS[event.from]} to ${STATUS_LABELS[event.to as Status]}` : `Started as ${STATUS_LABELS[event.to as Status]}`}
                </span>
                <span className="ml-2 text-muted-foreground">{formatWhen(event.at)}</span>
                {event.note && <p className="text-muted-foreground">{event.note}</p>}
              </li>
            ))}
          </ol>
        )}
      </Section>

      <Section id="reminders" title="Reminders">
        <Reminders app={app} />
      </Section>

      <Section id="follow-up" title="Follow-up email">
        <FollowUp app={app} />
      </Section>
    </div>
  );
}

export function ApplicationDetailView({ id }: { id: string }) {
  const application = useApplication(id);
  if (application.isPending) return <p role="status">Loading application</p>;
  if (application.isError) {
    const failure = toFailure(application.error, "Could not load this application. Try again.");
    return (
      <div className="flex flex-col gap-3">
        <FormError>{failure.kind === "missing" ? "This application could not be found." : failure.message}</FormError>
        <div className="flex gap-2">
          {failure.kind !== "missing" && (
            <Button variant="outline" onClick={() => void application.refetch()}>
              Try again
            </Button>
          )}
          <Link href="/applications" className="self-center text-sm underline">
            Back to the board
          </Link>
        </div>
      </div>
    );
  }
  return <Detail app={application.data} />;
}
