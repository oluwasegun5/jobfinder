import type {
  BackgroundRequest,
  ContentRequest,
  ContentResponse,
  CvResponse,
  FillDataResponse,
  LogAppliedResponse,
} from "../shared/messages";
import type { AtsName, FillData, FillReport, ReportItem, UsedDocuments } from "../shared/types";
import { FillSession, type CvFilePayload } from "./apply";
import { detectAts } from "./ats";
import { createPanel, type PanelView } from "./panel";
import { KEY_TEXT, planFill, skipText, usedFrom } from "./plan";

/**
 * The content script of an ATS page. It does nothing until the user presses a button (in this panel or in the toolbar
 * popup): no autofill on load. It never calls core-api and never sees the access token; it asks the service worker for
 * the values the current form can use, fills them, and reports what it did and what it left. It cannot submit the form.
 */

function send<R>(request: BackgroundRequest): Promise<R> {
  return chrome.runtime.sendMessage(request) as Promise<R>;
}

function start(): void {
  const detected = detectAts(location.hostname);
  if (!detected) return;
  const ats: AtsName = detected;

  const session = new FillSession();
  let data: FillData | null = null;
  let report: FillReport | undefined;
  let used: UsedDocuments = {};
  let logged = false;
  let busy = false;
  let cv: { key: string; file: CvFilePayload } | null = null;

  const show = (message: PanelView["message"], extra: Partial<PanelView> = {}): void => {
    panel.render({
      busy,
      message,
      job: data?.job ?? null,
      report,
      canLog: data?.job != null && report !== undefined && report.filled.length > 0,
      logged,
      ...extra,
    });
  };

  async function cvFor(ref: NonNullable<FillData["cv"]>): Promise<CvFilePayload | string> {
    const key = JSON.stringify(ref);
    if (cv?.key === key) return cv.file;
    const response = await send<CvResponse>({ type: "cv", ref });
    if (!response.ok) return response.message;
    cv = { key, file: { name: response.name, contentType: response.contentType, base64: response.base64 } };
    return cv.file;
  }

  async function runFill(overwrite: boolean): Promise<ContentResponse> {
    if (busy) return { ok: false, reason: "busy", message: "Already working on it." };
    busy = true;
    show({ tone: "info", text: "Reading your JobFinder data..." });
    try {
      const response = await send<FillDataResponse>({ type: "fill-data" });
      if (!response.ok) {
        busy = false;
        const text =
          response.reason === "signed_out"
            ? "You are signed out. Click the JobFinder button in the browser toolbar to sign in, then press Fill again."
            : response.message;
        show({ tone: "error", text });
        return { ok: false, reason: response.reason, message: text };
      }
      data = response.data;

      const plan = planFill(document, data, ats, { overwrite });
      if (plan.length === 0) {
        busy = false;
        const text = "No application form found on this page yet.";
        show({ tone: "info", text });
        return { ok: false, reason: "no_form", message: text };
      }

      let file: CvFilePayload | null = null;
      let cvProblem: string | null = null;
      if (data.cv && plan.some((i) => i.action === "fill" && i.key === "cv")) {
        const got = await cvFor(data.cv);
        if (typeof got === "string") cvProblem = got;
        else file = got;
      }

      const filled = session.apply(plan, file);
      const filledSet = new Set(filled.map((i) => i.el));
      const filledItems: ReportItem[] = filled.map((i) => ({ label: i.label, detail: KEY_TEXT[i.key ?? "screening"] }));
      const skippedItems: ReportItem[] = plan
        .filter((i) => !filledSet.has(i.el))
        .map((i) => ({
          label: i.label,
          detail: i.action === "fill" ? (cvProblem ? `Could not download your CV: ${cvProblem}` : "Could not be filled") : skipText(i, ats),
        }));
      const overwritable = overwrite ? 0 : plan.filter((i) => i.skip === "has_value").length;

      const result: FillReport = { filled: filledItems, skipped: skippedItems, overwritable, ats };
      report = result;
      used = usedFrom(data.used, filled);
      busy = false;
      show({
        tone: "success",
        text:
          filled.length === 0
            ? "Nothing could be filled. See below what needs your input."
            : `Filled ${filled.length} field${filled.length === 1 ? "" : "s"} (outlined in green). Check everything, then submit the form yourself.`,
      });
      return { ok: true, report: result };
    } catch (error) {
      busy = false;
      const text = error instanceof Error ? error.message : "Something went wrong.";
      show({ tone: "error", text });
      return { ok: false, reason: "error", message: text };
    }
  }

  const panel = createPanel({
    fill: () => void runFill(false),
    overwrite: () => void runFill(true),
    undo: () => {
      const restored = session.undo();
      report = undefined;
      show({ tone: "info", text: `Restored ${restored} field${restored === 1 ? "" : "s"} to what they were.` });
    },
    logApplied: () => {
      if (busy || logged || !data?.job) return;
      busy = true;
      show({ tone: "info", text: "Logging your application..." });
      void send<LogAppliedResponse>({
        type: "log-applied",
        jobId: data.job.id,
        applicationId: data.applicationId,
        packId: data.packId,
        used,
      }).then(
        (response) => {
          busy = false;
          if (response.ok && data) {
            logged = true;
            data = { ...data, applicationId: response.applicationId };
            show({ tone: "success", text: "Logged in your JobFinder tracker." });
          } else {
            show({ tone: "error", text: response.ok ? "Could not log the application." : response.message });
          }
        },
        () => {
          busy = false;
          show({ tone: "error", text: "Could not reach JobFinder. Try again." });
        },
      );
    },
  });

  chrome.runtime.onMessage.addListener((message: unknown, sender, sendResponse) => {
    if (sender.id !== chrome.runtime.id || typeof message !== "object" || message === null) return false;
    if ((message as ContentRequest).type !== "fill-request") return false;
    panel.open();
    void runFill(false).then(sendResponse);
    return true;
  });
}

start();
