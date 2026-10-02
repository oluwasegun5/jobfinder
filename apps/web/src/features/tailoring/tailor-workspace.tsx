"use client";

import { ArrowLeft } from "lucide-react";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { FormError } from "@/features/auth/form-parts";
import { useJob } from "@/features/jobs/queries";
import { toFailure } from "./errors";

import { AssistedApply } from "./assisted-apply";
import { CvReview } from "./cv-review";
import { ExportPanel } from "./export-panel";
import { PART_LABELS, isApproved, partOf, type PartType } from "./model";
import { PackProgress, StartForm } from "./pack-parts";
import { usePackForJob } from "./queries";
import { Section } from "./shared";
import { CoverLetterEditor, RegenerateControls, ScreeningAnswers } from "./writing-parts";

/**
 * Everything about tailoring for one job on one page: make the pack, see each part's progress, review the CV change by
 * change, edit the letter and the answers, approve and export, then apply. State lives in the pack query, so each
 * action updates what is on screen from the server's answer.
 */
export function TailorWorkspace({ jobId }: { jobId: string }) {
  const job = useJob(jobId);
  const pack = usePackForJob(jobId);

  const title = job.data?.title ?? "this job";
  const company = job.data?.company?.name;

  if (pack.isPending || job.isPending) return <p role="status">Loading</p>;

  if (job.isError || pack.isError) {
    const failure = toFailure(job.error ?? pack.error, "Could not load this page. Try again.");
    return (
      <div className="flex flex-col gap-3">
        <FormError>{failure.message}</FormError>
        <div className="flex gap-2">
          <Button
            variant="outline"
            onClick={() => {
              void job.refetch();
              void pack.refetch();
            }}
          >
            Try again
          </Button>
          <Link href={`/jobs/${jobId}`} className="self-center text-sm underline">
            Back to the job
          </Link>
        </div>
      </div>
    );
  }

  const current = pack.data;
  const doc = (type: PartType) => partOf(current, type)?.document;
  const resume = doc("TAILORED_RESUME");
  const letter = doc("COVER_LETTER");
  const answers = doc("SCREENING_ANSWERS");

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-2">
        <Link href={`/jobs/${jobId}`} className="inline-flex items-center gap-1 text-sm underline">
          <ArrowLeft className="size-4" aria-hidden /> Back to the job
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight">Tailor for {title}</h1>
        {company && <p className="text-muted-foreground">{company}</p>}
      </header>

      {!current ? (
        <Section id="start" title="Make your application">
          <StartForm jobId={jobId} />
        </Section>
      ) : (
        <>
          <Section id="progress" title="Progress">
            <PackProgress jobId={jobId} pack={current} />
            <details className="rounded-lg border p-3">
              <summary className="cursor-pointer text-sm font-medium">Make the pack again with other options</summary>
              <div className="mt-3">
                <StartForm jobId={jobId} initialInclude={(current.parts ?? []).map((p) => p.type as PartType)} />
              </div>
            </details>
          </Section>

          {resume && (
            <Section id="cv" title={PART_LABELS.TAILORED_RESUME}>
              <CvReview jobId={jobId} draft={resume} />
              {isApproved(resume) && resume.id && <ExportPanel documentId={resume.id} noun="CV" />}
            </Section>
          )}

          {letter && (
            <Section id="letter" title={PART_LABELS.COVER_LETTER}>
              <CoverLetterEditor jobId={jobId} draft={letter} />
              {!isApproved(letter) && <RegenerateControls key={`${letter.id}:${letter.version}`} jobId={jobId} draft={letter} type="COVER_LETTER" />}
              {isApproved(letter) && letter.id && <ExportPanel documentId={letter.id} noun="cover letter" />}
            </Section>
          )}

          {answers && (
            <Section id="answers" title={PART_LABELS.SCREENING_ANSWERS}>
              <ScreeningAnswers jobId={jobId} draft={answers} />
              {!isApproved(answers) && <RegenerateControls key={`${answers.id}:${answers.version}`} jobId={jobId} draft={answers} type="SCREENING_ANSWERS" />}
            </Section>
          )}

          <Section id="apply" title="Apply">
            <AssistedApply jobId={jobId} pack={current} applyUrl={job.data?.applyUrl} />
          </Section>
        </>
      )}
    </div>
  );
}
