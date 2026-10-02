import type { CvRef } from "../shared/types";
import { ApiError, authedOk } from "./api";
import { isUuid } from "./fill-data";

/** core-api caps uploads at 5 MB; a little headroom for rendered files. */
const MAX_BYTES = 6 * 1024 * 1024;

const TYPES: Record<string, { contentType: string; extension: string }> = {
  PDF: { contentType: "application/pdf", extension: "pdf" },
  DOCX: {
    contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    extension: "docx",
  },
};

export interface CvFile {
  name: string;
  contentType: string;
  base64: string;
}

export function safeFileName(name: string, extension: string): string {
  const base = name.replace(/[^\w.\- ]+/g, "_").trim().slice(0, 80) || "resume";
  return base.toLowerCase().endsWith(`.${extension}`) ? base : `${base}.${extension}`;
}

export function toBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

/** The pre-signed link is fetched from here, without credentials: it carries its own signature. */
async function download(url: string): Promise<Uint8Array> {
  const response = await fetch(url, { credentials: "omit", redirect: "error" });
  if (!response.ok) throw new ApiError(response.status);
  const declared = Number(response.headers.get("content-length") ?? 0);
  if (declared > MAX_BYTES) throw new Error("The CV file is larger than the extension will upload.");
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length > MAX_BYTES) throw new Error("The CV file is larger than the extension will upload.");
  return bytes;
}

/** Fetches the CV through the existing download-url flows. Only runs when the form has a resume field. */
export async function fetchCv(ref: CvRef): Promise<CvFile> {
  if (ref.kind === "document") {
    if (!isUuid(ref.documentId) || !isUuid(ref.fileId)) throw new Error("Invalid file reference.");
    const { documentId, fileId } = ref;
    const meta = await authedOk((api) =>
      api.GET("/documents/{id}/files/{fileId}/download", { params: { path: { id: documentId, fileId } } }),
    );
    const url = meta.data?.downloadUrl;
    if (!url) throw new Error("No download link.");
    const contentType = meta.data?.contentType ?? "application/pdf";
    const extension = contentType.includes("wordprocessingml") ? "docx" : "pdf";
    const bytes = await download(url);
    return { name: safeFileName(meta.data?.filename ?? "resume", extension), contentType, base64: toBase64(bytes) };
  }
  if (!isUuid(ref.resumeId)) throw new Error("Invalid file reference.");
  const { resumeId } = ref;
  const [list, link] = await Promise.all([
    authedOk((api) => api.GET("/resumes")),
    authedOk((api) => api.GET("/resumes/{id}/download-url", { params: { path: { id: resumeId } } })),
  ]);
  const url = link.data?.url;
  if (!url) throw new Error("No download link.");
  const resume = list.data?.find((r) => r.id === resumeId);
  const type = TYPES[resume?.fileType ?? "PDF"] ?? TYPES.PDF!;
  const bytes = await download(url);
  return { name: safeFileName(resume?.label ?? "resume", type.extension), contentType: type.contentType, base64: toBase64(bytes) };
}
