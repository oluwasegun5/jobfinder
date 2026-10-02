"use client";

import { useState } from "react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

import { toFailure, type Failure } from "./errors";
import {
  changeTitle,
  countStates,
  flagsForChange,
  unattachedFlags,
  unitLines,
  type Change,
  type Draft,
} from "./model";
import { useApproveDocument, usePatchDocument } from "./queries";
import { ApprovePanel, FactCheckBanner, FailureNotice, FlagList } from "./shared";

function Lines({ lines, empty }: { lines: string[]; empty: string }) {
  if (lines.length === 0) return <p className="text-sm italic text-muted-foreground">{empty}</p>;
  return (
    <div className="flex flex-col gap-1 text-sm">
      {lines.map((line, i) => (
        <p key={i} className="whitespace-pre-line break-words">
          {line}
        </p>
      ))}
    </div>
  );
}

function ChangeCard({
  draft,
  change,
  locked,
  busy,
  onSetState,
}: {
  draft: Draft;
  change: Change;
  locked: boolean;
  busy: boolean;
  onSetState: (change: Change, state: "ACCEPTED" | "REJECTED") => void;
}) {
  const title = changeTitle(change);
  const accepted = change.state === "ACCEPTED";
  const flags = flagsForChange(draft, change.id);
  const blocking = flags.some((f) => f.severity === "BLOCKING");
  return (
    <li
      className={cn(
        "flex flex-col gap-3 rounded-xl border p-3",
        blocking && accepted && "border-destructive/60",
        !accepted && "bg-muted/40",
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-sm font-semibold">
          {title}
          {change.edited && <span className="ml-2 text-xs font-normal text-muted-foreground">(edited by you)</span>}
        </h4>
        <div role="group" aria-label={`Decision for ${title}`} className="flex gap-2">
          <Button
            size="sm"
            variant={accepted ? "default" : "outline"}
            aria-pressed={accepted}
            aria-label={`Accept change: ${title}`}
            disabled={locked || busy}
            onClick={() => onSetState(change, "ACCEPTED")}
          >
            Accept
          </Button>
          <Button
            size="sm"
            variant={!accepted ? "default" : "outline"}
            aria-pressed={!accepted}
            aria-label={`Reject change: ${title}`}
            disabled={locked || busy}
            onClick={() => onSetState(change, "REJECTED")}
          >
            Reject
          </Button>
        </div>
      </div>
      {change.rationale && <p className="text-sm text-muted-foreground">Why: {change.rationale}</p>}
      <FlagList flags={flags} />
      <div className="grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-1 rounded-lg border p-2">
          <h5 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Your CV now</h5>
          <Lines lines={unitLines(change.before)} empty="Nothing (this would be new)." />
        </div>
        <div className={cn("flex flex-col gap-1 rounded-lg border p-2", accepted ? "border-primary/50" : "opacity-70")}>
          <h5 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Proposed {accepted ? "(accepted)" : "(rejected: not used)"}
          </h5>
          <Lines lines={unitLines(change.after)} empty="Removed." />
        </div>
      </div>
    </li>
  );
}

/**
 * The review screen of a tailored CV: every change next to the original, accept or reject one by one (each decision
 * is saved at once and the fact check is re-run by core-api), blocking flags that disable approval and say why, then
 * approval.
 */
export function CvReview({ jobId, draft }: { jobId: string; draft: Draft }) {
  const patch = usePatchDocument(jobId);
  const approve = useApproveDocument(jobId);
  const [failure, setFailure] = useState<Failure>();
  const [approveFailure, setApproveFailure] = useState<Failure>();

  const locked = draft.status === "APPROVED" || draft.status === "SUPERSEDED" || draft.status === "GENERATING";
  const changes = draft.changes ?? [];
  const counts = countStates(changes);
  const loose = unattachedFlags(draft);

  const setState = (change: Change, state: "ACCEPTED" | "REJECTED") => {
    if (!change.id || change.state === state || draft.id === undefined || draft.version === undefined) return;
    setFailure(undefined);
    patch.mutate(
      { id: draft.id, version: draft.version, operations: [{ op: "SET_STATE", changeId: change.id, state }] },
      { onError: (e) => setFailure(toFailure(e, "Could not save that decision. Try again.")) },
    );
  };

  return (
    <div className="flex flex-col gap-4">
      <FactCheckBanner draft={draft} />
      {loose.length > 0 && (
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">Notes that apply to the whole CV</h3>
          <FlagList flags={loose} />
        </div>
      )}
      <p role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {counts.total === 0
          ? "No changes were proposed: the model left your CV as it is."
          : `${counts.accepted} of ${counts.total} changes accepted, ${counts.rejected} rejected.`}
      </p>
      <FailureNotice failure={failure} />
      {changes.length > 0 && (
        <ol className="flex flex-col gap-3" aria-label="Proposed changes">
          {changes.map((change) => (
            <ChangeCard
              key={change.id}
              draft={draft}
              change={change}
              locked={locked}
              busy={patch.isPending}
              onSetState={setState}
            />
          ))}
        </ol>
      )}
      <ApprovePanel
        draft={draft}
        noun="CV"
        busy={approve.isPending}
        failure={approveFailure}
        onApprove={() => {
          if (!draft.id) return;
          setApproveFailure(undefined);
          approve.mutate(draft.id, { onError: (e) => setApproveFailure(toFailure(e, "Could not approve the CV. Try again.")) });
        }}
      />
    </div>
  );
}
