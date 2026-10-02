import { expect, test, type Page, type Route } from "@playwright/test";

/**
 * Job -> tailor -> accept/reject -> approve CV -> "I applied" -> tracker entry (P4.4 / P4.5).
 *
 * Unlike auth.spec and onboarding.spec this one needs no backend stack: the AI-backed endpoints cannot run in E2E
 * (they need an LLM, which E2E must not call), so the whole /api/core/* surface is a small stateful stand-in served
 * through Playwright routes. What it exercises for real is the web app: the production build, routing, the typed
 * client, the review screen's gating of Approve, assisted apply and the board. The stand-in follows the rules the
 * server enforces and that the UI depends on (ADR 0029, 0031, 0032): a blocking flag keeps a draft from being
 * approved until the change that causes it is rejected, edits need the current version, and creating an application
 * for the same job twice returns the first. core-api's own tests cover the server side of those rules.
 */
const JOB = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const PACK = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const CV = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const APP = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

type Change = { id: string; section: string; op: string; path: string; before: unknown; after: unknown; rationale: string; state: "ACCEPTED" | "REJECTED" };

function backend() {
  let packMade = false;
  let version = 1;
  let approved = false;
  let application: Record<string, unknown> | undefined;
  const changes: Change[] = [
    { id: "c1", section: "SUMMARY", op: "REPLACE", path: "summary", before: "Backend engineer.", after: "Backend engineer focused on payments.", rationale: "Matches the posting.", state: "ACCEPTED" },
    {
      id: "c2",
      section: "EXPERIENCE",
      op: "REPLACE",
      path: "experience[0]",
      before: { company: "Northwind", title: "Engineer", bullets: ["Built APIs."] },
      after: { company: "Globex", title: "Engineer", bullets: ["Built payment APIs."] },
      rationale: "Reworded.",
      state: "ACCEPTED",
    },
  ];

  const blocking = () => (changes.find((c) => c.id === "c2")?.state === "ACCEPTED" ? 1 : 0);
  const draft = () => ({
    id: CV,
    type: "TAILORED_RESUME",
    status: approved ? "APPROVED" : blocking() ? "FACT_CHECK_FAILED" : "DRAFT",
    version,
    job: { id: JOB, title: "Java Engineer", company: "Acme" },
    changes,
    factCheck: {
      passed: !blocking(),
      blocking: blocking(),
      warnings: 0,
      flags: blocking()
        ? [{ code: "NEW_EMPLOYER", severity: "BLOCKING", path: "experience[0].company", value: "Globex", message: "New employer", changeId: "c2" }]
        : [],
    },
  });
  const pack = () => ({
    id: PACK,
    status: "COMPLETE",
    job: { id: JOB, title: "Java Engineer", company: "Acme" },
    options: { include: ["TAILORED_RESUME"] },
    parts: [{ type: "TAILORED_RESUME", state: "READY", document: draft() }],
    version: 1,
  });

  const calls: string[] = [];

  async function handle(route: Route) {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api\/core/, "");
    const key = `${request.method()} ${path}`;
    calls.push(key);
    const body = request.postDataJSON?.() as Record<string, unknown> | undefined;
    const ok = (json: unknown, status = 200) => route.fulfill({ status, json });

    if (key === "POST /auth/refresh") return ok({ accessToken: "e2e-token", expiresIn: 900 });
    if (key === "GET /auth/me") return ok({ id: "u1", email: "e2e@example.test", role: "USER", emailVerified: true });
    if (key === "GET /profile") return ok({ onboardingCompleted: true });
    if (key === `GET /jobs/${JOB}`) {
      return ok({ id: JOB, title: "Java Engineer", company: { id: "c1", name: "Acme" }, status: "ACTIVE", description: "Build things.", skills: ["Java"], applyUrl: "https://acme.example/apply" });
    }
    if (key === "GET /application-packs") return ok({ items: packMade ? [{ id: PACK, status: "COMPLETE" }] : [] });
    if (key === `POST /jobs/${JOB}/application-pack`) {
      packMade = true;
      return ok(pack(), 201);
    }
    if (key === `GET /application-packs/${PACK}`) return ok(pack());
    if (key === `PATCH /documents/${CV}`) {
      if (body?.version !== version) return ok({ status: 409, code: "version_conflict", detail: "stale" }, 409);
      for (const op of (body.operations as { op: string; changeId: string; state: "ACCEPTED" | "REJECTED" }[]) ?? []) {
        const change = changes.find((c) => c.id === op.changeId);
        if (op.op === "SET_STATE" && change) change.state = op.state;
      }
      version += 1;
      return ok(draft());
    }
    if (key === `POST /documents/${CV}/approve`) {
      if (blocking()) return ok({ status: 409, code: "fact_check_failed", detail: "blocked" }, 409);
      approved = true;
      version += 1;
      return ok(draft());
    }
    if (key === `POST /documents/${CV}/render`) {
      return ok({ id: "f1", template: body?.template, format: body?.format, pageSize: body?.pageSize, filename: "cv.pdf", sizeBytes: 4096, downloadUrl: "https://files.example/cv.pdf?sig=1" });
    }
    if (key === "POST /applications") {
      const existed = Boolean(application);
      application ??= { id: APP, jobId: JOB, title: "Java Engineer", company: "Acme", status: "APPLIED", appliedAt: new Date().toISOString(), statusChangedAt: new Date().toISOString(), packId: body?.packId, resumeDocumentId: body?.resumeDocumentId, events: [{ id: "e1", to: "APPLIED", at: new Date().toISOString() }], reminders: [] };
      return ok(application, existed ? 200 : 201);
    }
    if (key === `PUT /jobs/${JOB}/applied`) return route.fulfill({ status: 204 });
    if (key === `GET /applications/${APP}`) return ok(application);
    if (key === "GET /applications") {
      const counts = { SAVED: 0, APPLIED: application ? 1 : 0, SCREENING: 0, INTERVIEW: 0, OFFER: 0, REJECTED: 0, WITHDRAWN: 0 };
      return ok({ board: { SAVED: [], APPLIED: application ? [application] : [], SCREENING: [], INTERVIEW: [], OFFER: [], REJECTED: [], WITHDRAWN: [] }, counts, truncated: false });
    }
    return route.fulfill({ status: 404, json: { status: 404, code: "e2e_unrouted", detail: key } });
  }

  return { handle, calls };
}

async function openApp(page: Page) {
  const api = backend();
  await page.route("**/api/core/**", (route) => api.handle(route));
  return api;
}

test("job -> tailor -> reject the flagged change -> approve the CV -> I applied -> tracker entry", async ({ page }) => {
  const api = await openApp(page);

  await page.goto(`/jobs/${JOB}`);
  await expect(page.getByRole("heading", { name: "Java Engineer" })).toBeVisible();
  await page.getByRole("link", { name: "Tailor for this job" }).click();

  // Tailor only the CV.
  await expect(page.getByRole("heading", { name: "Tailor for Java Engineer" })).toBeVisible();
  await page.getByRole("checkbox", { name: "Cover letter" }).uncheck();
  await page.getByRole("checkbox", { name: "Screening answers" }).uncheck();
  await page.getByRole("button", { name: "Tailor my CV" }).click();

  // Side by side, with the blocking flag prominent and Approve disabled for a stated reason.
  await expect(page.getByRole("heading", { name: "Tailored CV" })).toBeVisible();
  await expect(page.getByText("Backend engineer focused on payments.")).toBeVisible();
  await expect(page.getByText(/Fact check failed: 1 blocking flag/)).toBeVisible();
  await expect(page.getByText(/Blocking: An employer that is not in your CV/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve CV" })).toBeDisabled();
  await expect(page.getByText(/1 blocking fact-check flag: reject or edit the changes/)).toBeVisible();

  // Accept one, reject the one that introduced the invented employer.
  await page.getByRole("button", { name: "Reject change: Experience 1" }).click();
  await expect(page.getByText("1 of 2 changes accepted, 1 rejected.")).toBeVisible();
  await expect(page.getByText(/Fact check passed/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Accept change: Summary" })).toHaveAttribute("aria-pressed", "true");

  // Approve needs the person to vouch for the content.
  const approve = page.getByRole("button", { name: "Approve CV" });
  await expect(approve).toBeDisabled();
  await page.getByRole("checkbox", { name: /I vouch that everything in it is true/ }).check();
  await approve.click();
  await expect(page.getByText(/Approved\. This CV is final/)).toBeVisible();

  // Export.
  await page.getByRole("button", { name: "Export" }).click();
  const download = page.getByRole("link", { name: "Download cv.pdf" });
  await expect(download).toHaveAttribute("href", "https://files.example/cv.pdf?sig=1");

  // Assisted apply: the apply link opens in a new tab and is never followed by the app itself.
  const open = page.getByRole("link", { name: /Open application page/ });
  await expect(open).toHaveAttribute("href", "https://acme.example/apply");
  await expect(open).toHaveAttribute("target", "_blank");
  await expect(open).toHaveAttribute("rel", /noopener/);

  await page.getByRole("button", { name: "I applied" }).click();
  await expect(page.getByText("Added to your tracker as Applied.")).toBeVisible();
  await page.getByRole("link", { name: "Open the tracker entry" }).click();

  await expect(page.getByRole("heading", { name: "Java Engineer" })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/applications/${APP}$`));
  await expect(page.getByRole("list", { name: "Status history" })).toContainText("Started as Applied");

  // The board shows it in the Applied column.
  await page.getByRole("link", { name: "Back to the board" }).click();
  await expect(page.getByTestId("column-APPLIED").getByRole("link", { name: "Java Engineer" })).toBeVisible();

  // The CV was approved before it was attached, and the application carries it.
  expect(api.calls).toContain(`POST /documents/${CV}/approve`);
  expect(api.calls.filter((c) => c === "POST /applications")).toHaveLength(1);
});

test("the Applications link is in the navigation and the board is reachable from it", async ({ page }) => {
  await openApp(page);
  await page.goto("/dashboard");
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Applications" }).click();
  await expect(page).toHaveURL(/\/applications$/);
  await expect(page.getByRole("heading", { name: "Applications", level: 1 })).toBeVisible();
  await expect(page.getByText("Nothing tracked yet.")).toBeVisible();
});
