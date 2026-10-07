"use client";

import { AlertTriangle, Check, Copy, OctagonAlert, ShieldCheck } from "lucide-react";
import { useId, useRef, useState, type ReactNode } from "react";

import { InsufficientCreditsPrompt } from "@/components/billing/credits-prompt";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

import type { Failure } from "./errors";
import { approvalGate, explainFlag, isBlocking, type Draft, type Flag } from "./model";

/** A failed action in words, with a retry when trying again can help. Announced to screen readers. */
export function FailureNotice({ failure, onRetry, retrying }: { failure: Failure | undefined; onRetry?: () => void; retrying?: boolean }) {
  if (!failure) return null;
  if (failure.kind === "credits") return <InsufficientCreditsPrompt message={failure.message} />;
  return (
    <div role="alert" className="flex flex-col gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
      <p>{failure.message}</p>
      {onRetry && failure.retryable && (
        <div>
          <Button variant="outline" size="sm" onClick={onRetry} disabled={retrying}>
            Try again
          </Button>
        </div>
      )}
    </div>
  );
}

/**
 * Fact-check flags, blocking ones first and visually loud. Each says in plain words what was found and quotes the
 * value, so the person can judge it. The posting's injected instructions are explained, never hidden.
 */
export function FlagList({ flags, compact = false }: { flags: Flag[]; compact?: boolean }) {
  if (flags.length === 0) return null;
  const sorted = [...flags].sort((a, b) => Number(isBlocking(b)) - Number(isBlocking(a)));
  return (
    <ul className="flex flex-col gap-2" aria-label="Fact-check flags">
      {sorted.map((flag, index) => {
        const blocking = isBlocking(flag);
        const Icon = blocking ? OctagonAlert : AlertTriangle;
        return (
          <li
            key={`${flag.code}-${flag.path}-${index}`}
            className={cn(
              "flex gap-2 rounded-lg border px-3 py-2 text-sm",
              blocking ? "border-destructive/50 bg-destructive/10 text-destructive" : "border-amber-500/50 bg-amber-500/10",
            )}
          >
            <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
            <div className="flex flex-col gap-1">
              <p className="font-medium">
                {blocking ? "Blocking" : "Warning"}: {explainFlag(flag)}
              </p>
              {!compact && flag.value && (
                <p className="break-words">
                  Found: <q>{flag.value}</q>
                </p>
              )}
              {!compact && flag.path && <p className="text-xs opacity-80">Where: {flag.path}</p>}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** The headline of a document's fact check: passed, warnings only, or blocked. */
export function FactCheckBanner({ draft }: { draft: Draft }) {
  const blocking = draft.factCheck?.blocking ?? 0;
  const warnings = draft.factCheck?.warnings ?? 0;
  if (blocking > 0) {
    return (
      <p role="alert" className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm font-medium text-destructive">
        <OctagonAlert className="size-4 shrink-0" aria-hidden />
        Fact check failed: {blocking} blocking flag{blocking === 1 ? "" : "s"}. This cannot be approved until they are resolved.
      </p>
    );
  }
  if (warnings > 0) {
    return (
      <p role="status" className="flex items-center gap-2 rounded-lg border border-amber-500/50 bg-amber-500/10 px-3 py-2 text-sm font-medium">
        <AlertTriangle className="size-4 shrink-0" aria-hidden />
        {warnings} warning{warnings === 1 ? "" : "s"} to read before you approve. Nothing blocks approval, but you decide.
      </p>
    );
  }
  return (
    <p role="status" className="flex items-center gap-2 rounded-lg bg-muted px-3 py-2 text-sm">
      <ShieldCheck className="size-4 shrink-0" aria-hidden />
      Fact check passed: nothing in this document is missing from your CV.
    </p>
  );
}

/**
 * Approve, with the reason it is not possible when it is not, and an explicit statement that the person vouches for
 * the content. Approving makes the document final: it cannot be edited afterwards.
 */
export function ApprovePanel({
  draft,
  noun,
  busy,
  failure,
  onApprove,
}: {
  draft: Draft;
  noun: string;
  busy: boolean;
  failure: Failure | undefined;
  onApprove: () => void;
}) {
  const id = useId();
  const [vouched, setVouched] = useState(false);
  const gate = approvalGate(draft);
  const approved = draft.status === "APPROVED";

  if (approved) {
    return (
      <p role="status" className="flex items-center gap-2 rounded-lg bg-muted px-3 py-2 text-sm font-medium">
        <Check className="size-4" aria-hidden /> Approved. This {noun} is final and can no longer be edited.
      </p>
    );
  }
  return (
    <section aria-labelledby={`${id}-heading`} className="flex flex-col gap-3 rounded-lg border p-3">
      <h4 id={`${id}-heading`} className="text-sm font-medium">
        Approve this {noun}
      </h4>
      {gate.reasons.length > 0 && (
        <ul id={`${id}-reasons`} className="list-disc pl-5 text-sm text-destructive">
          {gate.reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}
      <label className="flex items-start gap-2 text-sm">
        <input
          type="checkbox"
          className="mt-1 size-4"
          checked={vouched}
          disabled={!gate.allowed}
          onChange={(e) => setVouched(e.target.checked)}
        />
        <span>
          I have read this {noun} and I vouch that everything in it is true. Approving makes it final, and I am responsible
          for what I send to an employer.
        </span>
      </label>
      <div>
        <Button
          onClick={onApprove}
          disabled={!gate.allowed || !vouched || busy}
          aria-describedby={gate.reasons.length > 0 ? `${id}-reasons` : undefined}
        >
          {busy ? "Approving" : `Approve ${noun}`}
        </Button>
      </div>
      <FailureNotice failure={failure} />
    </section>
  );
}

/** Copies text to the clipboard and says so; if the browser refuses, says that instead of failing silently. */
export function CopyButton({ text, label, className }: { text: string; label: string; className?: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setState("copied");
    } catch {
      setState("failed");
    }
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setState("idle"), 2500);
  };

  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <Button variant="outline" size="sm" onClick={() => void copy()} aria-label={`Copy ${label}`}>
        {state === "copied" ? <Check /> : <Copy />} {state === "copied" ? "Copied" : "Copy"}
      </Button>
      <span role="status" aria-live="polite" className="text-xs text-muted-foreground">
        {state === "copied" ? `${label} copied` : state === "failed" ? "Could not copy: select the text and copy it yourself" : ""}
      </span>
    </span>
  );
}

export function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section aria-labelledby={`${id}-heading`} className="flex scroll-mt-4 flex-col gap-3" id={id}>
      <h2 id={`${id}-heading`} className="text-lg font-semibold">
        {title}
      </h2>
      {children}
    </section>
  );
}
