"use client";

import { Download } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { safeHref } from "@/features/jobs/format";

import { formatWhen, toFailure, type Failure } from "./errors";
import type { RenderedFile } from "./model";
import { useRenderDocument } from "./queries";
import { FailureNotice } from "./shared";

const selectClass =
  "h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

function size(bytes: number | undefined) {
  if (bytes === undefined) return "";
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * Export of an approved CV or letter: ATS (plain, one column, for application systems) or styled, PDF or DOCX. The
 * download link is short-lived and signed, so it is shown for the click and not stored.
 */
export function ExportPanel({ documentId, noun }: { documentId: string; noun: string }) {
  const render = useRenderDocument();
  const [template, setTemplate] = useState<"ATS" | "STYLED">("ATS");
  const [format, setFormat] = useState<"PDF" | "DOCX">("PDF");
  const [pageSize, setPageSize] = useState<"A4" | "LETTER">("A4");
  const [files, setFiles] = useState<RenderedFile[]>([]);
  const [failure, setFailure] = useState<Failure>();

  const run = () => {
    setFailure(undefined);
    render.mutate(
      { id: documentId, template, format, pageSize },
      {
        onSuccess: (file) => setFiles((prev) => [file, ...prev.filter((f) => f.id !== file.id)]),
        onError: (e) => setFailure(toFailure(e, "Could not export. Try again.")),
      },
    );
  };

  return (
    <section aria-label={`Export ${noun}`} className="flex flex-col gap-3 rounded-lg border p-3">
      <h4 className="text-sm font-medium">Export this {noun}</h4>
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`tpl-${documentId}`}>Layout</Label>
          <select id={`tpl-${documentId}`} className={selectClass} value={template} onChange={(e) => setTemplate(e.target.value as "ATS" | "STYLED")}>
            <option value="ATS">ATS-friendly (plain, one column)</option>
            <option value="STYLED">Styled</option>
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`fmt-${documentId}`}>Format</Label>
          <select id={`fmt-${documentId}`} className={selectClass} value={format} onChange={(e) => setFormat(e.target.value as "PDF" | "DOCX")}>
            <option value="PDF">PDF</option>
            <option value="DOCX">Word (DOCX)</option>
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`page-${documentId}`}>Page size</Label>
          <select id={`page-${documentId}`} className={selectClass} value={pageSize} onChange={(e) => setPageSize(e.target.value as "A4" | "LETTER")}>
            <option value="A4">A4</option>
            <option value="LETTER">US Letter</option>
          </select>
        </div>
        <Button onClick={run} disabled={render.isPending}>
          <Download /> {render.isPending ? "Exporting" : "Export"}
        </Button>
      </div>
      <FailureNotice failure={failure} onRetry={run} retrying={render.isPending} />
      {files.length > 0 && (
        <ul className="flex flex-col gap-2" aria-label="Exported files">
          {files.map((file) => {
            const href = safeHref(file.downloadUrl);
            return (
              <li key={file.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                {href ? (
                  <a href={href} rel="noopener noreferrer nofollow" download={file.filename} className="font-medium underline">
                    Download {file.filename}
                  </a>
                ) : (
                  <span>{file.filename} (no download link)</span>
                )}
                <span className="text-muted-foreground">
                  {file.template === "ATS" ? "ATS" : "Styled"} · {file.format} · {size(file.sizeBytes)}
                  {file.expiresAt ? ` · link expires ${formatWhen(file.expiresAt)}` : ""}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
