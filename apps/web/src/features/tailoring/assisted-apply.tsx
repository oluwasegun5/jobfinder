"use client";

import { Check, ExternalLink } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { useCreateApplication } from "@/features/applications/queries";
import { safeHref } from "@/features/jobs/format";
import { changeJobState, refreshAfterChange } from "@/features/jobs/queries";

import { toFailure, type Failure } from "./errors";
import { PART_LABELS, answersOf, isApproved, letterOf, letterText, partOf, type Pack, type PartType } from "./model";
import { CopyButton, FailureNotice } from "./shared";

const linkClass =
  "inline-flex h-9 items-center gap-1.5 rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground outline-none hover:bg-primary/80 focus-visible:ring-3 focus-visible:ring-ring/50";

/**
 * Assisted apply: the platform never submits anything. It opens the employer's page, lets the person copy the approved
 * texts into the form, and records the application when they say they applied (PLAN.md section 8).
 */
export function AssistedApply({ jobId, pack, applyUrl }: { jobId: string; pack: Pack; applyUrl: string | undefined }) {
  const queryClient = useQueryClient();
  const create = useCreateApplication();
  const [pasted, setPasted] = useState("");
  const [failure, setFailure] = useState<Failure>();
  const [result, setResult] = useState<{ id: string; existed: boolean }>();

  const resume = partOf(pack, "TAILORED_RESUME")?.document;
  const letter = partOf(pack, "COVER_LETTER")?.document;
  const answers = partOf(pack, "SCREENING_ANSWERS")?.document;
  const documents: [PartType, typeof resume][] = [
    ["TAILORED_RESUME", resume],
    ["COVER_LETTER", letter],
    ["SCREENING_ANSWERS", answers],
  ];

  const known = safeHref(applyUrl);
  const typed = pasted.trim();
  const typedHref = safeHref(typed);
  const href = known ?? typedHref;
  const typedInvalid = !known && typed.length > 0 && !typedHref;

  const approvedLetter = isApproved(letter) && letter ? letterText(letterOf(letter)) : undefined;
  const approvedAnswers = isApproved(answers) && answers ? answersOf(answers).filter((a) => a.answer) : [];
  const missing = documents.filter(([, d]) => d && !isApproved(d)).map(([type]) => PART_LABELS[type]);

  const confirm = () => {
    setFailure(undefined);
    create.mutate(
      {
        jobId,
        packId: pack.id,
        resumeDocumentId: isApproved(resume) ? resume?.id : undefined,
        coverLetterDocumentId: isApproved(letter) ? letter?.id : undefined,
        screeningAnswersDocumentId: isApproved(answers) ? answers?.id : undefined,
        url: known ? undefined : typedHref,
      },
      {
        onSuccess: ({ application, existed }) => {
          setResult({ id: application.id ?? "", existed });
          // Also tell the job list: it feeds the "For you" ranking. Failing to is not worth bothering the person.
          void changeJobState(jobId, "apply")
            .then(() => refreshAfterChange(queryClient, jobId, "apply"))
            .catch(() => undefined);
        },
        onError: (e) => setFailure(toFailure(e, "Could not add this to your tracker. Try again.")),
      },
    );
  };

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">
        Nothing is sent for you. Open the employer&apos;s page, paste your approved texts into their form, submit it yourself, then come back and confirm.
      </p>

      <section aria-label="Documents for this application" className="flex flex-col gap-1 text-sm">
        <h3 className="font-medium">Documents</h3>
        <ul className="flex flex-col gap-1">
          {documents.map(([type, doc]) => (
            <li key={type} className="flex items-center gap-2">
              {isApproved(doc) ? <Check className="size-4" aria-hidden /> : <span className="size-4" aria-hidden />}
              {PART_LABELS[type]}: {!doc ? "not made" : isApproved(doc) ? "approved" : "not approved yet"}
            </li>
          ))}
        </ul>
        {missing.length > 0 && <p className="text-muted-foreground">Only approved documents can be copied or attached to the application record.</p>}
      </section>

      <section aria-label="Application page" className="flex flex-col gap-2">
        <h3 className="text-sm font-medium">1. Open the application page</h3>
        {known ? null : (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="apply-link">This job has no apply link. Paste the link to the application page (optional)</Label>
            <input
              id="apply-link"
              type="url"
              inputMode="url"
              className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm"
              value={pasted}
              aria-invalid={typedInvalid}
              onChange={(e) => setPasted(e.target.value)}
            />
            {typedInvalid && <p role="alert" className="text-sm text-destructive">Only http or https links can be opened.</p>}
          </div>
        )}
        {href && (
          <div>
            <a href={href} target="_blank" rel="noopener noreferrer nofollow" className={linkClass}>
              Open application page <ExternalLink className="size-4" aria-hidden />
              <span className="sr-only">(opens in a new tab)</span>
            </a>
          </div>
        )}
      </section>

      <section aria-label="Texts to copy" className="flex flex-col gap-3">
        <h3 className="text-sm font-medium">2. Copy your approved texts</h3>
        {approvedLetter === undefined && approvedAnswers.length === 0 && (
          <p className="text-sm text-muted-foreground">Approve the cover letter or the answers to copy them here.</p>
        )}
        {approvedLetter !== undefined && (
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">Cover letter</p>
            <CopyButton text={approvedLetter} label="cover letter" />
          </div>
        )}
        {approvedAnswers.length > 0 && (
          <ul className="flex flex-col gap-3" aria-label="Answers to copy">
            {approvedAnswers.map((a) => (
              <li key={a.id} className="flex flex-col gap-1 text-sm">
                <span className="font-medium">{a.question}</span>
                <span className="whitespace-pre-line text-muted-foreground">{a.answer}</span>
                <CopyButton text={a.answer} label={`answer to: ${a.question}`} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-label="Confirm" className="flex flex-col gap-2">
        <h3 className="text-sm font-medium">3. Tell us when you have applied</h3>
        {result ? (
          <p role="status" className="rounded-lg bg-muted px-3 py-2 text-sm">
            {result.existed ? "This job was already in your tracker." : "Added to your tracker as Applied."}{" "}
            <Link href={`/applications/${result.id}`} className="font-medium underline">
              Open the tracker entry
            </Link>
          </p>
        ) : (
          <>
            <div>
              <Button onClick={confirm} disabled={create.isPending}>
                <Check /> {create.isPending ? "Saving" : "I applied"}
              </Button>
            </div>
            <FailureNotice failure={failure} onRetry={confirm} retrying={create.isPending} />
          </>
        )}
      </section>
    </div>
  );
}
