// Design review screenshots of the signed-in screens (ADR 0043, G4 Part 2): phone and desktop, light and dark.
// core-api is stood in for in the browser (as the stubbed e2e specs do), so only the web app needs to run:
//   npx next start -p 3151 &
//   node scripts/design-screenshots-app.mjs http://localhost:3151
// Writes PNGs to docs/design/screenshots/part2/ at the repository root. Data is synthetic.
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "@playwright/test";

const baseUrl = process.argv[2] ?? "http://localhost:3151";
const only = process.argv[3]; // optional page name filter
const outDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../docs/design/screenshots/part2");

const JOB = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const job = (id, title, company, extra = {}) => ({
  id,
  title,
  company: { id: `c-${company}`, name: company },
  city: "Lagos",
  country: "NG",
  workMode: "HYBRID",
  employmentType: "FULL_TIME",
  seniority: "SENIOR",
  status: "ACTIVE",
  saved: false,
  applied: false,
  postedAt: new Date(Date.now() - 2 * 86400_000).toISOString(),
  summary: "Build and run the services behind a payments product used across West Africa. You will own APIs end to end.",
  ...extra,
});
const feedItem = (id, title, company, score, source = "LLM_SCORED", extra = {}) => ({
  job: job(id, title, company),
  matchScore: score,
  scoreSource: source,
  feedScore: score,
  adjustment: 0,
  reasons: [],
  strengths: ["Five years of Java and Spring Boot", "Payments domain experience"],
  gaps: ["No Kubernetes in your CV"],
  ...extra,
});
const feed = {
  items: [
    feedItem(JOB, "Senior Java Engineer", "Paystack Labs", 94),
    feedItem("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Backend Developer", "Kuda Systems", 81),
    feedItem("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "Platform Engineer", "Flutterline", 63),
    feedItem("dddddddd-dddd-4ddd-8ddd-dddddddddddd", "Data Analyst", "Interswitch Works", 38, "LLM_SCORED"),
    feedItem("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "Support Engineer", "Andela Next", 58, "STAGE2_ONLY", { strengths: [], gaps: [] }),
  ],
};
const detail = {
  ...job(JOB, "Senior Java Engineer", "Paystack Labs"),
  description:
    "We are looking for a senior engineer to join the payments platform team.\n\nYou will design and ship APIs, review code, and mentor two colleagues.\n\nRequirements: Java 21, Spring Boot, PostgreSQL, a habit of writing tests.",
  skills: ["Java", "Spring Boot", "PostgreSQL", "Kafka"],
  applyUrl: "https://example.test/apply",
  hidden: false,
  similarAvailable: true,
  listings: [{ source: "Remotive", url: "https://example.test/job/1", attribution: { name: "Remotive", text: "Jobs via", url: "https://example.test" } }],
};
const card = (id, title, company, status, extra = {}) => ({ id, title, company, status, ...extra });
const board = {
  board: {
    SAVED: [card("1", "Backend Engineer", "Kuda Systems", "SAVED")],
    APPLIED: [card("2", "Platform Engineer", "Flutterline", "APPLIED", { appliedAt: "2026-10-01T10:00:00Z" })],
    SCREENING: [card("3", "Java Engineer", "Paystack Labs", "SCREENING")],
    INTERVIEW: [card("4", "Staff Engineer", "Interswitch Works", "INTERVIEW")],
    OFFER: [],
    REJECTED: [],
    WITHDRAWN: [],
  },
  counts: { SAVED: 1, APPLIED: 1, SCREENING: 1, INTERVIEW: 1, OFFER: 0, REJECTED: 0, WITHDRAWN: 0 },
  truncated: false,
};
const billingMe = {
  plan: { code: "free", name: "Free", monthlyCredits: 300 },
  status: "FREE",
  balance: 240,
  grantedThisPeriod: 300,
  usedThisPeriod: 60,
  trial: {
    state: "ACTIVE",
    unlocked: 100,
    remaining: 200,
    milestones: ["EMAIL_VERIFIED", "CV_PARSED"],
    tranches: [
      { milestone: "EMAIL_VERIFIED", code: "M1", credits: 30, unlocked: true },
      { milestone: "CV_PARSED", code: "M2", credits: 70, unlocked: true },
      { milestone: "ONBOARDING_COMPLETED", code: "M3", credits: 100, unlocked: false },
      { milestone: "MATCHES_VIEWED", code: "M4", credits: 100, unlocked: false },
    ],
  },
  allowance: { dailyCap: 500, used: 40, remaining: 460, resetsAt: "2026-10-13T00:00:00Z" },
};
const plans = {
  plans: [
    { code: "free", name: "Free", monthlyCredits: 300, prices: [] },
    { code: "pro", name: "Pro", monthlyCredits: 6000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 900 }] },
  ],
  packs: [
    { id: "small", name: "Small pack", credits: 1000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 500 }] },
    { id: "large", name: "Large pack", credits: 5000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 2000 }] },
  ],
  rolloverCapCredits: 0,
};

const activationStep = (step, count, fromPrevious, overall) => ({ step, count, fromPrevious, overall });
const activation = {
  from: "2026-09-13",
  to: "2026-10-12",
  steps: [
    activationStep("SIGNUP", 200, undefined, 1),
    activationStep("EMAIL_VERIFIED", 150, 0.75, 0.75),
    activationStep("CV_UPLOADED", 90, 0.6, 0.45),
    activationStep("CV_PARSED", 80, 0.8889, 0.4),
    activationStep("ONBOARDING_COMPLETED", 60, 0.75, 0.3),
    activationStep("FIRST_MATCHES_VIEWED", 30, 0.5, 0.15),
    activationStep("FIRST_TAILORED_CV", 6, 0.2, 0.03),
  ],
  cohorts: [
    { weekStart: "2026-09-07", counts: [120, 90, 50, 45, 30, 15, 3] },
    { weekStart: "2026-09-14", counts: [80, 60, 40, 35, 30, 15, 3] },
  ],
  trial: { granted: 140, hold: 4, denied: 6 },
};

const routes = {
  "GET /admin/activation": () => activation,
  "POST /auth/refresh": () => ({ accessToken: "stub", expiresIn: 900 }),
  "GET /auth/me": () => ({ id: "u1", email: "ada@example.test", role: "ADMIN", emailVerified: true, aiConsent: true }),
  "GET /profile": () => ({ onboardingCompleted: true }),
  "GET /actuator/health": () => ({ status: "UP" }),
  "GET /feed": () => feed,
  "GET /jobs": () => ({ items: feed.items.map((i) => i.job), hasMore: false }),
  [`GET /jobs/${JOB}`]: () => detail,
  "GET /saved-jobs": () => ({ items: [] }),
  "GET /saved-searches": () => ({ items: [] }),
  "GET /applications": () => board,
  "GET /billing/me": () => billingMe,
  "GET /billing/plans": () => plans,
  "GET /billing/ledger": () => ({
    items: [
      { id: 3, createdAt: "2026-10-03T10:00:00Z", delta: -4, reason: "AI_USAGE", balanceAfter: 296 },
      { id: 2, createdAt: "2026-10-01T10:00:00Z", delta: 300, reason: "PLAN_GRANT", balanceAfter: 300 },
    ],
  }),
  "GET /notifications/preferences": () => ({ jobAlertsEnabled: true, digestEnabled: true, remindersEnabled: true, productUpdatesEnabled: false }),
  "GET /me/consent": () => ({ aiProcessing: true, granted: true }),
  "GET /interview-sessions": () => ({ items: [], totalPages: 1, page: 0 }),
  "GET /resumes": () => [],
};

const pages = [
  { name: "dashboard", path: "/dashboard", wait: "Dashboard" },
  { name: "feed", path: "/feed", wait: "Senior Java Engineer" },
  { name: "jobs", path: "/jobs", wait: "Senior Java Engineer" },
  { name: "job-detail", path: `/jobs/${JOB}`, wait: "Senior Java Engineer" },
  { name: "saved-jobs", path: "/saved-jobs", wait: "Jobs you save show up here." },
  { name: "applications", path: "/applications", wait: "Backend Engineer" },
  { name: "billing", path: "/billing", wait: "Add credits" },
  { name: "settings-notifications", path: "/settings/notifications", wait: "Notifications" },
  { name: "onboarding", path: "/onboarding/cv", wait: "Upload your CV" },
  { name: "admin-activation", path: "/admin/activation", wait: "Funnel" },
  { name: "interviews", path: "/interviews", wait: "Mock interviews" },
];
const viewports = [
  { name: "mobile", width: 390, height: 844, isMobile: true, deviceScaleFactor: 2 },
  { name: "desktop", width: 1280, height: 800, isMobile: false, deviceScaleFactor: 1 },
];

await mkdir(outDir, { recursive: true });
const browser = await chromium.launch();
try {
  for (const viewport of viewports) {
    for (const colorScheme of ["light", "dark"]) {
      const context = await browser.newContext({
        viewport: { width: viewport.width, height: viewport.height },
        isMobile: viewport.isMobile,
        hasTouch: viewport.isMobile,
        deviceScaleFactor: viewport.deviceScaleFactor,
        colorScheme,
        reducedMotion: "reduce",
        timezoneId: "Europe/London",
        locale: "en-GB",
      });
      await context.route("**/api/core/**", async (route) => {
        const request = route.request();
        const key = `${request.method()} ${new URL(request.url()).pathname.replace(/^\/api\/core/, "")}`;
        const handler = routes[key];
        if (!handler) {
          console.warn("unstubbed", key);
          return route.fulfill({ status: 404, json: { status: 404, code: "not_found", detail: "stub" } });
        }
        return route.fulfill({ status: 200, json: handler() });
      });
      const page = await context.newPage();
      for (const target of pages) {
        if (only && target.name !== only) continue;
        await page.goto(new URL(target.path, baseUrl).toString(), { waitUntil: "networkidle" });
        await page.locator("main").getByText(target.wait).first().waitFor();
        await page.evaluate(() => document.fonts.ready);
        const file = path.join(outDir, `${target.name}-${viewport.name}-${colorScheme}.png`);
        await page.screenshot({ path: file, fullPage: true });
        console.log(file);
      }
      if (viewport.isMobile && (!only || only === "menu")) {
        await page.goto(new URL("/feed", baseUrl).toString(), { waitUntil: "networkidle" });
        await page.getByRole("button", { name: "Open menu" }).click();
        await page.getByRole("link", { name: "Dashboard" }).waitFor();
        const file = path.join(outDir, `menu-${viewport.name}-${colorScheme}.png`);
        await page.screenshot({ path: file });
        console.log(file);
      }
      await context.close();
    }
  }
} finally {
  await browser.close();
}
