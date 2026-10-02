"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useRef, useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { FormError } from "@/features/auth/form-parts";
import { safeHref } from "@/features/jobs/format";
import { toFailure, type Failure } from "@/features/tailoring/errors";
import { cn } from "@/lib/utils";

import {
  STATUSES,
  STATUS_LABELS,
  cardsIn,
  countIn,
  findCard,
  isStatus,
  reminderLabel,
  type ApplicationCard,
  type Status,
} from "./model";
import { useBoard, useCreateApplication, useMoveApplication } from "./queries";

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-background px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";
const inputClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm";

function formatDay(iso: string | undefined) {
  if (!iso) return undefined;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? undefined : d.toLocaleDateString("en", { dateStyle: "medium" });
}

function Card_({
  card,
  onMove,
  onDragStart,
  moving,
}: {
  card: ApplicationCard;
  onMove: (card: ApplicationCard, to: Status) => void;
  onDragStart: (id: string, e: React.DragEvent) => void;
  moving: boolean;
}) {
  const title = card.title ?? "Untitled";
  const reminder = reminderLabel(card.nextReminderAt);
  const applied = formatDay(card.appliedAt);
  return (
    <li
      draggable
      onDragStart={(e) => card.id && onDragStart(card.id, e)}
      data-testid={`card-${card.id}`}
      className="cursor-grab active:cursor-grabbing"
    >
      <Card size="sm">
        <CardContent className="flex flex-col gap-2 text-sm">
          <div className="flex flex-col">
            <Link href={`/applications/${card.id}`} className="font-medium underline-offset-2 hover:underline">
              {title}
            </Link>
            {card.company && <span className="text-muted-foreground">{card.company}</span>}
          </div>
          {(applied || reminder) && (
            <div className="flex flex-wrap gap-x-3 text-xs text-muted-foreground">
              {applied && <span>Applied {applied}</span>}
              {reminder && <span>{reminder}</span>}
            </div>
          )}
          <div className="flex flex-col gap-1">
            <Label htmlFor={`move-${card.id}`} className="text-xs text-muted-foreground">
              Move {title} to
            </Label>
            <select
              id={`move-${card.id}`}
              className={selectClass}
              value={card.status}
              disabled={moving}
              onChange={(e) => isStatus(e.target.value) && onMove(card, e.target.value)}
            >
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {STATUS_LABELS[s]}
                </option>
              ))}
            </select>
          </div>
        </CardContent>
      </Card>
    </li>
  );
}

function ManualEntry({ onDone }: { onDone: (title: string) => void }) {
  const create = useCreateApplication();
  const [title, setTitle] = useState("");
  const [company, setCompany] = useState("");
  const [url, setUrl] = useState("");
  const [status, setStatus] = useState<Status>("APPLIED");
  const [notes, setNotes] = useState("");
  const [failure, setFailure] = useState<Failure>();
  const [fieldError, setFieldError] = useState<string>();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setFailure(undefined);
    if (!title.trim()) return setFieldError("A title is required.");
    if (url.trim() && !safeHref(url.trim())) return setFieldError("Use an http or https link.");
    setFieldError(undefined);
    create.mutate(
      { title: title.trim(), company: company.trim() || undefined, url: url.trim() || undefined, status, notes: notes.trim() || undefined },
      {
        onSuccess: () => onDone(title.trim()),
        onError: (err) => setFailure(toFailure(err, "Could not add the application. Try again.")),
      },
    );
  };

  return (
    <form onSubmit={submit} aria-label="Add an application" className="flex flex-col gap-3 rounded-xl border p-4" noValidate>
      <p className="text-sm text-muted-foreground">For a job you found somewhere else. Only the title is required.</p>
      <div className="grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="manual-title">Job title</Label>
          <input id="manual-title" className={inputClass} value={title} maxLength={400} required aria-invalid={Boolean(fieldError && !title.trim())} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="manual-company">Company</Label>
          <input id="manual-company" className={inputClass} value={company} maxLength={400} onChange={(e) => setCompany(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="manual-url">Link to the posting</Label>
          <input id="manual-url" type="url" inputMode="url" className={inputClass} value={url} onChange={(e) => setUrl(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="manual-status">Status</Label>
          <select id="manual-status" className={selectClass} value={status} onChange={(e) => isStatus(e.target.value) && setStatus(e.target.value)}>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="manual-notes">Notes</Label>
        <Textarea id="manual-notes" value={notes} maxLength={10000} onChange={(e) => setNotes(e.target.value)} />
      </div>
      <FormError>{fieldError ?? failure?.message}</FormError>
      <div>
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? "Adding" : "Add application"}
        </Button>
      </div>
    </form>
  );
}

type Notice = { kind: "error" | "ok"; text: string };

/**
 * The application board: one column per status from GET /applications?grouped=true. Cards are dragged between columns
 * (the move is shown at once and undone with the reason if core-api refuses it) or moved with the select on each card,
 * which is the keyboard and touch way to do the same thing.
 */
export function Board() {
  const board = useBoard();
  const move = useMoveApplication();
  const [notice, setNotice] = useState<Notice>();
  const [adding, setAdding] = useState(false);
  const [over, setOver] = useState<Status>();
  const dragging = useRef<string | undefined>(undefined);

  const moveTo = (id: string, to: Status) => {
    const card = findCard(board.data, id);
    if (!card || card.status === to) return;
    setNotice(undefined);
    move.mutate(
      { id, to },
      {
        onSuccess: () => setNotice({ kind: "ok", text: `Moved ${card.title ?? "application"} to ${STATUS_LABELS[to]}.` }),
        onError: (e) =>
          setNotice({
            kind: "error",
            text: `${toFailure(e, "Could not move it.").message} ${card.title ?? "The application"} is back in ${STATUS_LABELS[card.status as Status] ?? "its column"}.`,
          }),
      },
    );
  };

  const onDragStart = (id: string, e: React.DragEvent) => {
    dragging.current = id;
    e.dataTransfer?.setData("text/plain", id);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
  };

  const onDrop = (to: Status, e: React.DragEvent) => {
    e.preventDefault();
    setOver(undefined);
    const id = e.dataTransfer?.getData("text/plain") || dragging.current;
    dragging.current = undefined;
    if (id) moveTo(id, to);
  };

  if (board.isPending) return <p role="status">Loading applications</p>;
  if (board.isError && !board.data) {
    return (
      <div className="flex flex-col gap-3">
        <FormError>{toFailure(board.error, "Could not load your applications. Try again.").message}</FormError>
        <div>
          <Button variant="outline" onClick={() => void board.refetch()}>
            Try again
          </Button>
        </div>
      </div>
    );
  }

  const total = STATUSES.reduce((sum, s) => sum + countIn(board.data, s), 0);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant={adding ? "outline" : "default"} aria-expanded={adding} onClick={() => setAdding((v) => !v)}>
          <Plus /> {adding ? "Close" : "Add an application"}
        </Button>
        <p className="text-sm text-muted-foreground">
          {total === 0 ? "Nothing tracked yet." : `${total} application${total === 1 ? "" : "s"}. Drag a card to another column, or use the menu on a card.`}
        </p>
      </div>
      {adding && (
        <ManualEntry
          onDone={(title) => {
            setAdding(false);
            setNotice({ kind: "ok", text: `Added ${title}.` });
          }}
        />
      )}
      <div aria-live="polite" className="min-h-0">
        {notice?.kind === "error" && <FormError>{notice.text}</FormError>}
        {notice?.kind === "ok" && (
          <p role="status" className="rounded-lg bg-muted px-3 py-2 text-sm">
            {notice.text}
          </p>
        )}
      </div>
      {board.data.truncated && (
        <p className="text-sm text-muted-foreground">Only the most recent applications are shown.</p>
      )}
      <div className="grid gap-4 md:auto-cols-[minmax(15rem,1fr)] md:grid-flow-col md:overflow-x-auto md:pb-2">
        {STATUSES.map((status) => {
          const cards = cardsIn(board.data, status);
          return (
            <section
              key={status}
              aria-labelledby={`col-${status}`}
              data-testid={`column-${status}`}
              onDragOver={(e) => {
                e.preventDefault();
                if (over !== status) setOver(status);
              }}
              onDragLeave={() => setOver((o) => (o === status ? undefined : o))}
              onDrop={(e) => onDrop(status, e)}
              className={cn("flex min-w-0 flex-col gap-3 rounded-xl bg-muted/40 p-3", over === status && "ring-2 ring-ring")}
            >
              <h2 id={`col-${status}`} className="flex items-center justify-between text-sm font-semibold">
                {STATUS_LABELS[status]}
                <Badge variant="secondary" aria-label={`${countIn(board.data, status)} applications`}>
                  {countIn(board.data, status)}
                </Badge>
              </h2>
              {cards.length === 0 ? (
                <p className="text-sm text-muted-foreground">No applications here.</p>
              ) : (
                <ul className="flex flex-col gap-2" aria-label={`${STATUS_LABELS[status]} applications`}>
                  {cards.map((card) => (
                    <Card_ key={card.id} card={card} onMove={(c, to) => c.id && moveTo(c.id, to)} onDragStart={onDragStart} moving={move.isPending && move.variables?.id === card.id} />
                  ))}
                </ul>
              )}
            </section>
          );
        })}
      </div>
    </div>
  );
}
