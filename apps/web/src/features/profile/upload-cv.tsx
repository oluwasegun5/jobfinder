"use client";

import { Upload } from "lucide-react";
import { useRef, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { problemMessage } from "@/features/auth/api-errors";
import { FormError } from "@/features/auth/form-parts";

import { ApiProblem, useUploadResume, type Resume } from "./queries";

const MAX_BYTES = 5 * 1024 * 1024;

/** Quick checks for a friendlier message; core-api sniffs the real content type and size and has the last word. */
function problemWithFile(file: File): string | null {
  if (!/\.(pdf|docx)$/i.test(file.name)) return "Please choose a PDF or DOCX file.";
  if (file.size === 0) return "That file is empty.";
  if (file.size > MAX_BYTES) return "CVs can be at most 5 MB.";
  return null;
}

export function UploadCv({
  onUploaded,
  submitLabel = "Upload CV",
}: {
  onUploaded?: (resume: Resume) => void;
  submitLabel?: string;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [error, setError] = useState("");
  const upload = useUploadResume();

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const file = input.current?.files?.[0];
    if (!file) {
      setError("Choose a CV to upload.");
      return;
    }
    const invalid = problemWithFile(file);
    if (invalid) {
      setError(invalid);
      return;
    }
    setError("");
    try {
      const resume = await upload.mutateAsync(file);
      if (input.current) input.current.value = "";
      onUploaded?.(resume);
    } catch (failure) {
      setError(problemMessage(failure instanceof ApiProblem ? failure.problem : undefined, "Could not upload your CV."));
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-3">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="cv-file">CV file</Label>
        <input
          ref={input}
          id="cv-file"
          name="file"
          type="file"
          accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          aria-describedby="cv-file-hint"
          className="block w-full text-sm file:mr-3 file:rounded-lg file:border file:border-input file:bg-background file:px-3 file:py-1.5 file:text-sm file:font-medium hover:file:bg-muted"
        />
        <p id="cv-file-hint" className="text-xs text-muted-foreground">
          PDF or DOCX, up to 5 MB.
        </p>
      </div>
      <FormError>{error}</FormError>
      <Button type="submit" size="lg" disabled={upload.isPending} className="self-start">
        <Upload />
        {upload.isPending ? "Uploading…" : submitLabel}
      </Button>
    </form>
  );
}
