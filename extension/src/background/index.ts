import type {
  BackgroundRequest,
  CvResponse,
  FillDataResponse,
  LoginResponse,
  LogAppliedResponse,
  StatusResponse,
} from "../shared/messages";
import { ApiError, login, logout, SignedOutError } from "./api";
import { fetchCv } from "./cv";
import { loadFillData } from "./fill-data";
import { logApplied } from "./log-applied";
import { loadAuth, protectSession } from "./session";

/**
 * The service worker: the only place that holds the access token and the only place that talks to core-api. Content
 * scripts and the popup send it messages and get back the values they need and nothing else
 * (docs/adr/0035-chrome-extension.md).
 */

void protectSession();
chrome.runtime.onInstalled.addListener(() => void protectSession());

type Response = StatusResponse | LoginResponse | FillDataResponse | CvResponse | LogAppliedResponse | { ok: true };

function failure(error: unknown): { ok: false; reason: "signed_out" | "error"; message: string } {
  if (error instanceof SignedOutError) {
    return { ok: false, reason: "signed_out", message: "Sign in from the JobFinder toolbar button." };
  }
  if (error instanceof ApiError) {
    return { ok: false, reason: "error", message: `JobFinder answered with an error (${error.status}).` };
  }
  return { ok: false, reason: "error", message: error instanceof Error ? error.message : "Something went wrong." };
}

async function handle(request: BackgroundRequest, senderUrl: string | undefined, fromContentScript: boolean): Promise<Response> {
  switch (request.type) {
    case "status": {
      const auth = await loadAuth();
      return { signedIn: auth !== null, email: auth?.email };
    }
    case "login": {
      // Only our own pages (the popup) may send a password; a content script never can.
      if (fromContentScript) return { ok: false, message: "Sign in from the toolbar popup." };
      const outcome = await login(String(request.email), String(request.password));
      if (outcome === "ok") return { ok: true, email: String(request.email).trim().toLowerCase() };
      return {
        ok: false,
        message: outcome === "rejected" ? "Check your email and password." : "Could not reach JobFinder. Try again.",
      };
    }
    case "logout": {
      await logout();
      return { ok: true };
    }
    case "fill-data": {
      if (!senderUrl) return { ok: false, reason: "error", message: "No page." };
      try {
        return { ok: true, data: await loadFillData(senderUrl) };
      } catch (error) {
        return failure(error);
      }
    }
    case "cv": {
      try {
        return { ok: true, ...(await fetchCv(request.ref)) };
      } catch (error) {
        return failure(error);
      }
    }
    case "log-applied": {
      try {
        return { ok: true, ...(await logApplied(request)) };
      } catch (error) {
        return failure(error);
      }
    }
  }
}

chrome.runtime.onMessage.addListener((message: unknown, sender, sendResponse) => {
  // Only messages from our own extension's scripts and pages (never from another extension or a web page).
  if (sender.id !== chrome.runtime.id || typeof message !== "object" || message === null) return false;
  const fromContentScript = sender.tab !== undefined;
  handle(message as BackgroundRequest, sender.url, fromContentScript).then(sendResponse, (error: unknown) => {
    sendResponse(failure(error));
  });
  return true;
});
