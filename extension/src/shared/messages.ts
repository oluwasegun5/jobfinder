import type { CvRef, FillData, FillReport, UsedDocuments } from "./types";

/** Requests to the service worker. The popup sends status/login/logout; the content script the rest. */
export type BackgroundRequest =
  | { type: "status" }
  | { type: "login"; email: string; password: string }
  | { type: "logout" }
  | { type: "fill-data" }
  | { type: "cv"; ref: CvRef }
  | { type: "log-applied"; jobId: string; applicationId: string | null; packId: string | null; used: UsedDocuments };

export type StatusResponse = { signedIn: boolean; email?: string };

export type LoginResponse = { ok: true; email: string } | { ok: false; message: string };

/** `signed_out` means: ask the user to sign in from the toolbar popup (the password never reaches a page). */
export type Failure = { ok: false; reason: "signed_out" | "error"; message: string };

export type FillDataResponse = { ok: true; data: FillData } | Failure;

export type CvResponse =
  | { ok: true; name: string; contentType: string; base64: string }
  | Failure;

export type LogAppliedResponse = { ok: true; applicationId: string; status: string } | Failure;

/** Popup (or anything else of ours) to the content script of the active tab. */
export type ContentRequest = { type: "fill-request" };

export type ContentResponse =
  | { ok: true; report: FillReport }
  | { ok: false; reason: "signed_out" | "no_form" | "busy" | "error"; message: string };
