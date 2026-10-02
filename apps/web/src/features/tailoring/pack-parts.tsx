"use client";

import { CheckCircle2, CircleAlert, Clock, Loader2, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useId, useState } from "react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

import { capMessage, toFailure, type Failure } from "./errors";
import {
  LENGTH_LABELS,
  PART_LABELS,
  PART_TYPES,
  TONE_LABELS,
  type Length,
  type Pack,
  type PackPart,
  type PartType,
  type Tone,
} from "./model";
import { useCreatePack, useRetryPack } from "./queries";
import { FailureNotice } from "./shared";

const selectClass =
  "h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

/** Parts in flight: each shown as working, because the server makes them inside one request. */
function Working({ parts }: { parts: PartType[] }) {
  return (
    <ul role="status" aria-live="polite" aria-label="Progress" className="flex flex-col gap-2 text-sm">
      {parts.map((type) => (
        <li key={type} className="flex items-center gap-2">
          <Loader2 className="size-4 animate-spin" aria-hidden /> Making: {PART_LABELS[type]}
        </li>
      ))}
      <li className="text-muted-foreground">This can take a minute. You can leave this page open.</li>
    </ul>
  );
}

/**
 * The options of a pack: which parts, tone, length and notes. "Tailor my CV only" is the pack with one part.
 */
export function StartForm({ jobId, initialInclude }: { jobId: string; initialInclude?: PartType[] }) {
  const id = useId();
  const create = useCreatePack(jobId);
  const [include, setInclude] = useState<PartType[]>(initialInclude ?? PART_TYPES);
  const [tone, setTone] = useState<Tone>("FORMAL");
  const [length, setLength] = useState<Length>("STANDARD");
  const [notes, setNotes] = useState("");
  const [failure, setFailure] = useState<Failure>();

  const toggle = (type: PartType) =>
    setInclude((current) => (current.includes(type) ? current.filter((t) => t !== type) : [...current, type]));
  const writes = include.some((t) => t !== "TAILORED_RESUME");

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setFailure(undefined);
    create.mutate(
      { include: PART_TYPES.filter((t) => include.includes(t)), tone, length, notes: notes.trim() || undefined },
      { onError: (err) => setFailure(toFailure(err, "Could not make the pack. Try again.")) },
    );
  };

  if (create.isPending) return <Working parts={PART_TYPES.filter((t) => include.includes(t))} />;

  return (
    <form onSubmit={submit} className="flex flex-col gap-4" aria-label="Tailor for this job">
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">What to make</legend>
        {PART_TYPES.map((type) => (
          <label key={type} className="flex items-center gap-2 text-sm">
            <input type="checkbox" className="size-4" checked={include.includes(type)} onChange={() => toggle(type)} />
            {PART_LABELS[type]}
          </label>
        ))}
        <p className="text-xs text-muted-foreground">
          Choose only the tailored CV to skip the letter and the answers. Everything is made from your primary CV and never adds a fact it does not show.
        </p>
      </fieldset>
      {writes && (
        <>
          <div className="flex flex-wrap gap-3">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${id}-tone`}>Tone</Label>
              <select id={`${id}-tone`} className={selectClass} value={tone} onChange={(e) => setTone(e.target.value as Tone)}>
                {(Object.keys(TONE_LABELS) as Tone[]).map((t) => (
                  <option key={t} value={t}>
                    {TONE_LABELS[t]}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${id}-length`}>Length</Label>
              <select id={`${id}-length`} className={selectClass} value={length} onChange={(e) => setLength(e.target.value as Length)}>
                {(Object.keys(LENGTH_LABELS) as Length[]).map((l) => (
                  <option key={l} value={l}>
                    {LENGTH_LABELS[l]}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${id}-notes`}>Anything to stress (optional)</Label>
            <Textarea id={`${id}-notes`} value={notes} maxLength={1000} onChange={(e) => setNotes(e.target.value)} />
          </div>
        </>
      )}
      <FailureNotice failure={failure} />
      {failure?.code === "resume_required" && (
        <p className="text-sm">
          <Link href="/profile/resumes" className="underline">
            Go to your CVs
          </Link>
        </p>
      )}
      <div>
        <Button type="submit" disabled={include.length === 0}>
          {include.length === 1 && include[0] === "TAILORED_RESUME" ? "Tailor my CV" : "Make my application pack"}
        </Button>
      </div>
    </form>
  );
}

function PartIcon({ state }: { state: PackPart["state"] }) {
  if (state === "READY") return <CheckCircle2 className="size-4 shrink-0" aria-hidden />;
  if (state === "PENDING") return <Loader2 className="size-4 shrink-0 animate-spin" aria-hidden />;
  if (state === "BLOCKED_BY_CAP") return <Clock className="size-4 shrink-0" aria-hidden />;
  return <CircleAlert className="size-4 shrink-0 text-destructive" aria-hidden />;
}

function stateText(part: PackPart): string {
  switch (part.state) {
    case "READY":
      return part.document?.status === "APPROVED" ? "Approved" : "Ready for your review";
    case "PENDING":
      return "Being made";
    case "FAILED":
      return "Failed";
    case "BLOCKED_BY_CAP":
      return "Waiting for your daily AI allowance";
    case "MISSING":
      return "Draft was deleted";
    default:
      return "";
  }
}

/** One line per part of the pack with its state, its typed error, and a retry for what did not finish. */
export function PackProgress({ jobId, pack }: { jobId: string; pack: Pack }) {
  const retry = useRetryPack(jobId);
  const [failure, setFailure] = useState<Failure>();
  const parts = pack.parts ?? [];
  const retryable = parts.filter((p) => p.state === "FAILED" || p.state === "BLOCKED_BY_CAP" || p.state === "MISSING");

  const run = (types?: PartType[]) => {
    if (!pack.id) return;
    setFailure(undefined);
    retry.mutate({ packId: pack.id, parts: types }, { onError: (e) => setFailure(toFailure(e, "Could not retry. Try again.")) });
  };

  return (
    <div className="flex flex-col gap-3">
      <ul aria-label="Pack progress" className="flex flex-col gap-2">
        {parts.map((part) => (
          <li key={part.type} className="flex flex-col gap-1 rounded-lg border p-3 text-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="flex items-center gap-2 font-medium">
                <PartIcon state={part.state} />
                {part.type ? PART_LABELS[part.type] : "Part"}: {stateText(part)}
              </span>
              {(part.state === "FAILED" || part.state === "BLOCKED_BY_CAP" || part.state === "MISSING") && part.type && (
                <Button variant="outline" size="sm" disabled={retry.isPending} onClick={() => run([part.type as PartType])} aria-label={`Retry ${PART_LABELS[part.type]}`}>
                  <RefreshCw /> Retry
                </Button>
              )}
            </div>
            {part.state === "BLOCKED_BY_CAP" && <p className="text-muted-foreground">{capMessage(part.error?.resetsAt)}</p>}
            {(part.state === "FAILED" || part.state === "MISSING") && (
              <p className="text-destructive">{part.error?.message ?? "This part could not be made."}</p>
            )}
          </li>
        ))}
      </ul>
      {pack.status === "GENERATING" && (
        <p role="status" className="text-sm text-muted-foreground">
          The pack is still being made. This page updates by itself.
        </p>
      )}
      <FailureNotice failure={failure} onRetry={() => run()} retrying={retry.isPending} />
      {retryable.length > 1 && (
        <div>
          <Button variant="outline" disabled={retry.isPending} onClick={() => run()}>
            <RefreshCw /> Retry all that did not finish
          </Button>
        </div>
      )}
    </div>
  );
}
