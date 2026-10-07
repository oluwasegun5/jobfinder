"use client";

import { Download, FileText, Star, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { InsufficientCreditsPrompt } from "@/components/billing/credits-prompt";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";

import { formatDateTime } from "@/features/admin/format";
import { useAllowance } from "@/features/billing/queries";

import { parseFailureMessage } from "./content-draft";
import {
  ApiProblem,
  REPARSABLE_ERRORS,
  fetchDownloadUrl,
  useDeleteResume,
  useReparseResume,
  useResumes,
  useSetPrimaryResume,
  type Resume,
} from "./queries";
import { UploadCv } from "./upload-cv";

function formatSize(bytes = 0) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function ParseBadge({ resume }: { resume: Resume }) {
  if (resume.parseStatus === "PENDING") return <Badge variant="secondary">Reading…</Badge>;
  if (resume.parseStatus === "FAILED") return <Badge variant="destructive">Couldn&apos;t read</Badge>;
  return <Badge variant="outline">Ready</Badge>;
}

function ResumeRow({ resume, onError }: { resume: Resume; onError: (message: string) => void }) {
  const [confirming, setConfirming] = useState(false);
  const setPrimary = useSetPrimaryResume();
  const remove = useDeleteResume();
  const reparse = useReparseResume();
  const id = resume.id ?? "";
  const failed = resume.parseStatus === "FAILED";
  const capped = failed && resume.parseError === "ai_daily_cap_reached";
  const outOfCredits = failed && resume.parseError === "insufficient_credits";
  const canReparse = failed && REPARSABLE_ERRORS.includes(resume.parseError ?? "");
  // The reset time is only fetched when someone is looking at a CV the daily limit blocked.
  const allowance = useAllowance({ enabled: capped });
  const label = resume.label ?? "CV";

  const fail = (error: unknown, fallback: string) =>
    onError(problemMessage(error instanceof ApiProblem ? error.problem : undefined, fallback));

  async function download() {
    try {
      window.open(await fetchDownloadUrl(id), "_blank", "noopener,noreferrer");
    } catch (error) {
      fail(error, "Could not create a download link.");
    }
  }

  return (
    <li>
      <Card size="sm">
        <CardContent className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
            <span className="font-medium">{label}</span>
            <span className="text-xs text-muted-foreground">
              {resume.fileType} · {formatSize(resume.sizeBytes)}
            </span>
            {resume.primary && (
              <Badge>
                <Star /> Primary
              </Badge>
            )}
            <ParseBadge resume={resume} />
          </div>
          {failed && (
            <p className="text-sm text-muted-foreground">
              {parseFailureMessage(resume.parseError)}
              {capped && allowance.data?.resetsAt && (
                <>
                  {" "}
                  You can try again after <time dateTime={allowance.data.resetsAt}>{formatDateTime(allowance.data.resetsAt)}</time>.
                </>
              )}
            </p>
          )}
          {outOfCredits && <InsufficientCreditsPrompt />}
          <div className="flex flex-wrap gap-2">
            {canReparse && (
              <Button
                variant="outline"
                size="sm"
                aria-label={`Read ${label} again`}
                disabled={reparse.isPending}
                onClick={() => reparse.mutate(id, { onError: (e) => fail(e, "Could not read this CV again.") })}
              >
                {reparse.isPending ? "Starting…" : "Try again"}
              </Button>
            )}
            {!resume.primary && (
              <Button
                variant="outline"
                size="sm"
                aria-label={`Make ${label} primary`}
                disabled={setPrimary.isPending}
                onClick={() => setPrimary.mutate(id, { onError: (e) => fail(e, "Could not change your primary CV.") })}
              >
                Make primary
              </Button>
            )}
            <Link
              href={`/profile?resume=${id}`}
              aria-label={`Edit the profile content of ${label}`}
              className="inline-flex h-7 items-center rounded-lg border px-2.5 text-[0.8rem] font-medium hover:bg-muted"
            >
              Edit content
            </Link>
            <Button variant="outline" size="sm" aria-label={`Download ${label}`} onClick={() => void download()}>
              <Download /> Download
            </Button>
            {confirming ? (
              <>
                <Button
                  variant="destructive"
                  size="sm"
                  disabled={remove.isPending}
                  aria-label={`Confirm delete ${label}`}
                  onClick={() => remove.mutate(id, { onError: (e) => fail(e, "Could not delete this CV.") })}
                >
                  {remove.isPending ? "Deleting…" : "Yes, delete"}
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setConfirming(false)}>
                  Cancel
                </Button>
              </>
            ) : (
              <Button variant="ghost" size="sm" aria-label={`Delete ${label}`} onClick={() => setConfirming(true)}>
                <Trash2 /> Delete
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
    </li>
  );
}

/** Lists the user's CVs (newest first) with primary selection, download and delete, plus an uploader. */
export function ResumeManager() {
  const resumes = useResumes();
  const [error, setError] = useState("");

  return (
    <div className="flex flex-col gap-6">
      <FormError>{error}</FormError>
      {resumes.isPending && (
        <p role="status" className="text-sm text-muted-foreground">
          Loading your CVs…
        </p>
      )}
      {resumes.isError && <FormError>We couldn&apos;t load your CVs.</FormError>}
      {resumes.data && resumes.data.length === 0 && (
        <p className="text-sm text-muted-foreground">You haven&apos;t uploaded a CV yet.</p>
      )}
      {resumes.data && resumes.data.length > 0 && (
        <ul aria-label="Your CVs" className="flex flex-col gap-3">
          {resumes.data.map((resume) => (
            <ResumeRow key={resume.id} resume={resume} onError={setError} />
          ))}
        </ul>
      )}
      <section aria-labelledby="upload-heading" className="flex flex-col gap-3">
        <h2 id="upload-heading" className="text-base font-medium">
          Upload another CV
        </h2>
        <UploadCv submitLabel="Upload CV" onUploaded={() => setError("")} />
      </section>
    </div>
  );
}
