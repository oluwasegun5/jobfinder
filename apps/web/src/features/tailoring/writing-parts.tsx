"use client";

import { RefreshCw } from "lucide-react";
import { useId, useState } from "react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

import { toFailure, type Failure } from "./errors";
import { LENGTH_LABELS, TONE_LABELS, answersOf, letterOf, letterText, type Draft, type Length, type Tone } from "./model";
import { useApproveDocument, usePatchDocument, useRegenerate, type Operation } from "./queries";
import { ApprovePanel, CopyButton, FactCheckBanner, FailureNotice, FlagList } from "./shared";

const selectClass =
  "h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

function optionOf<T extends string>(options: Draft["options"], key: string, fallback: T, allowed: readonly string[]): T {
  const value = options?.[key];
  return typeof value === "string" && allowed.includes(value) ? (value as T) : fallback;
}

/** Tone, length and notes, and a button that writes the document again. The old draft is kept as history. */
export function RegenerateControls({ jobId, draft, type }: { jobId: string; draft: Draft; type: "COVER_LETTER" | "SCREENING_ANSWERS" }) {
  const id = useId();
  const regenerate = useRegenerate(jobId, type);
  const [tone, setTone] = useState<Tone>(optionOf(draft.options, "tone", "FORMAL", Object.keys(TONE_LABELS)));
  const [length, setLength] = useState<Length>(optionOf(draft.options, "length", "STANDARD", Object.keys(LENGTH_LABELS)));
  const [notes, setNotes] = useState(typeof draft.options?.notes === "string" ? draft.options.notes : "");
  const [failure, setFailure] = useState<Failure>();
  const noun = type === "COVER_LETTER" ? "letter" : "answers";

  const run = () => {
    setFailure(undefined);
    regenerate.mutate(
      { tone, length, notes: notes.trim() || undefined },
      { onError: (e) => setFailure(toFailure(e, `Could not write the ${noun} again. Try again.`)) },
    );
  };

  return (
    <section aria-label={`Write the ${noun} again`} className="flex flex-col gap-3 rounded-lg border p-3">
      <h4 className="text-sm font-medium">Write the {noun} again</h4>
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
        <p className="text-xs text-muted-foreground">It can shape the wording but cannot add facts your CV does not show.</p>
      </div>
      <p className="text-xs text-muted-foreground">Writing again makes a new draft. Edits you have not saved here are not carried over.</p>
      <FailureNotice failure={failure} onRetry={run} retrying={regenerate.isPending} />
      <div>
        <Button variant="outline" onClick={run} disabled={regenerate.isPending}>
          <RefreshCw /> {regenerate.isPending ? "Writing" : `Write ${noun} again`}
        </Button>
      </div>
    </section>
  );
}

function LetterForm({ jobId, draft }: { jobId: string; draft: Draft }) {
  const patch = usePatchDocument(jobId);
  const approve = useApproveDocument(jobId);
  const letter = letterOf(draft);
  const [values, setValues] = useState<Record<string, string>>(() => ({
    salutation: letter.salutation,
    closing: letter.closing,
    signature: letter.signature,
    ...Object.fromEntries(letter.paragraphs.map((p, i) => [`paragraphs[${i}]`, p])),
  }));
  const [failure, setFailure] = useState<Failure>();
  const [approveFailure, setApproveFailure] = useState<Failure>();
  const locked = draft.status === "APPROVED" || draft.status === "SUPERSEDED";

  const original: Record<string, string> = {
    salutation: letter.salutation,
    closing: letter.closing,
    signature: letter.signature,
    ...Object.fromEntries(letter.paragraphs.map((p, i) => [`paragraphs[${i}]`, p])),
  };
  const dirty = Object.keys(values).filter((k) => values[k] !== original[k]);

  const save = () => {
    if (!draft.id || draft.version === undefined || dirty.length === 0) return;
    setFailure(undefined);
    const operations: Operation[] = dirty.map((path) => ({ op: "EDIT", path, after: values[path].trim() }));
    patch.mutate(
      { id: draft.id, version: draft.version, operations },
      { onError: (e) => setFailure(toFailure(e, "Could not save the letter. Try again.")) },
    );
  };

  const field = (path: string, label: string, multiline: boolean) => (
    <div className="flex flex-col gap-1.5" key={path}>
      <Label htmlFor={`letter-${path}`}>{label}</Label>
      {multiline ? (
        <Textarea
          id={`letter-${path}`}
          value={values[path] ?? ""}
          rows={5}
          maxLength={1600}
          disabled={locked}
          onChange={(e) => setValues((v) => ({ ...v, [path]: e.target.value }))}
        />
      ) : (
        <input
          id={`letter-${path}`}
          className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50 md:text-sm"
          value={values[path] ?? ""}
          disabled={locked}
          onChange={(e) => setValues((v) => ({ ...v, [path]: e.target.value }))}
        />
      )}
    </div>
  );

  return (
    <div className="flex flex-col gap-4">
      <FactCheckBanner draft={draft} />
      <FlagList flags={draft.factCheck?.flags ?? []} />
      {field("salutation", "Salutation", false)}
      {letter.paragraphs.map((_, i) => field(`paragraphs[${i}]`, `Paragraph ${i + 1}`, true))}
      {field("closing", "Closing", false)}
      {field("signature", "Signature", false)}
      <FailureNotice failure={failure} />
      <div className="flex flex-wrap items-center gap-3">
        {!locked && (
          <Button onClick={save} disabled={dirty.length === 0 || patch.isPending}>
            {patch.isPending ? "Saving" : dirty.length === 0 ? "No unsaved changes" : "Save changes"}
          </Button>
        )}
        <CopyButton text={letterText({ ...letter, ...{ salutation: values.salutation ?? "", closing: values.closing ?? "", signature: values.signature ?? "", paragraphs: letter.paragraphs.map((_, i) => values[`paragraphs[${i}]`] ?? "") } })} label="cover letter" />
      </div>
      {dirty.length > 0 && !locked && <p className="text-sm text-muted-foreground">You have unsaved changes. Save them before approving: approval uses the saved text.</p>}
      <ApprovePanel
        draft={draft}
        noun="cover letter"
        busy={approve.isPending || dirty.length > 0}
        failure={approveFailure}
        onApprove={() => {
          if (!draft.id) return;
          setApproveFailure(undefined);
          approve.mutate(draft.id, { onError: (e) => setApproveFailure(toFailure(e, "Could not approve the letter. Try again.")) });
        }}
      />
    </div>
  );
}

/** The cover letter: every paragraph editable, saved with the fact check re-run, then approved. */
export function CoverLetterEditor({ jobId, draft }: { jobId: string; draft: Draft }) {
  // Keyed by the stored version so the fields start from what the server holds after every save or regeneration.
  return <LetterForm key={`${draft.id}:${draft.version}`} jobId={jobId} draft={draft} />;
}

const STATUS_LABELS: Record<string, string> = {
  FROM_PROFILE: "From your profile",
  GENERATED: "Written by AI: check it",
  NEEDS_INPUT: "Needs your answer",
  USER_PROVIDED: "Your answer",
};

function AnswersForm({ jobId, draft }: { jobId: string; draft: Draft }) {
  const patch = usePatchDocument(jobId);
  const approve = useApproveDocument(jobId);
  const answers = answersOf(draft);
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(answers.map((a) => [a.id, a.answer])));
  const [failure, setFailure] = useState<Failure>();
  const [approveFailure, setApproveFailure] = useState<Failure>();
  const locked = draft.status === "APPROVED" || draft.status === "SUPERSEDED";
  const dirty = answers.filter((a) => (values[a.id] ?? "") !== a.answer && (values[a.id] ?? "").trim() !== "");

  const save = () => {
    if (!draft.id || draft.version === undefined || dirty.length === 0) return;
    setFailure(undefined);
    const operations: Operation[] = dirty.map((a) => ({ op: "EDIT", path: `answers.${a.id}`, after: values[a.id].trim() }));
    patch.mutate(
      { id: draft.id, version: draft.version, operations },
      { onError: (e) => setFailure(toFailure(e, "Could not save your answers. Try again.")) },
    );
  };

  return (
    <div className="flex flex-col gap-4">
      <FactCheckBanner draft={draft} />
      <FlagList flags={draft.factCheck?.flags ?? []} />
      <ul className="flex flex-col gap-4" aria-label="Screening questions">
        {answers.map((a) => {
          const open = a.status === "NEEDS_INPUT" && (values[a.id] ?? "").trim() === "";
          return (
            <li key={a.id} className="flex flex-col gap-1.5 rounded-xl border p-3">
              <Label htmlFor={`answer-${a.id}`}>{a.question}</Label>
              <span className={open ? "text-xs font-medium text-destructive" : "text-xs text-muted-foreground"}>
                {STATUS_LABELS[a.status] ?? a.status}
              </span>
              {a.hint && a.status === "NEEDS_INPUT" && <p id={`hint-${a.id}`} className="text-sm text-muted-foreground">{a.hint}</p>}
              <Textarea
                id={`answer-${a.id}`}
                value={values[a.id] ?? ""}
                rows={3}
                maxLength={1200}
                disabled={locked}
                aria-invalid={open}
                aria-describedby={a.hint ? `hint-${a.id}` : undefined}
                onChange={(e) => setValues((v) => ({ ...v, [a.id]: e.target.value }))}
              />
              {(values[a.id] ?? "") !== "" && <CopyButton text={values[a.id]} label={`answer to: ${a.question}`} />}
            </li>
          );
        })}
      </ul>
      <FailureNotice failure={failure} />
      {!locked && (
        <div>
          <Button onClick={save} disabled={dirty.length === 0 || patch.isPending}>
            {patch.isPending ? "Saving" : dirty.length === 0 ? "No unsaved answers" : `Save ${dirty.length} answer${dirty.length === 1 ? "" : "s"}`}
          </Button>
        </div>
      )}
      <ApprovePanel
        draft={draft}
        noun="answers"
        busy={approve.isPending || dirty.length > 0}
        failure={approveFailure}
        onApprove={() => {
          if (!draft.id) return;
          setApproveFailure(undefined);
          approve.mutate(draft.id, { onError: (e) => setApproveFailure(toFailure(e, "Could not approve the answers. Try again.")) });
        }}
      />
    </div>
  );
}

/** The screening answers: typed answers for the questions that need you, edits to the rest, then approval. */
export function ScreeningAnswers({ jobId, draft }: { jobId: string; draft: Draft }) {
  return <AnswersForm key={`${draft.id}:${draft.version}`} jobId={jobId} draft={draft} />;
}
