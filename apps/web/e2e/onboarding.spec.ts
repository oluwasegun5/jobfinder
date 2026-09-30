import { expect, test, type Page } from "@playwright/test";

import { fill, signUpAndSignIn } from "./support";

/**
 * Real CV parsing needs an LLM, which E2E must not call (and which is not configured on a local stack), so the
 * parse RESULT is the one thing stubbed here: GET /resumes/{id}/content. Everything else is the real stack: the
 * upload, the profile / content / preferences saves (validated server-side) and the reads after a reload. Parsing
 * itself is covered by core-api's ResumeParsingTests and ai-service's tests.
 */
const CONTENT_URL = /\/api\/core\/resumes\/[^/]+\/content$/;

const pdf = {
  name: "jordan-reyes-cv.pdf",
  mimeType: "application/pdf",
  buffer: Buffer.from("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n"),
};

const parsed = {
  schema_version: 1,
  contact: { full_name: "Jordan Reyes", email: "jordan@example.test", location: "London, UK", links: [] },
  headline: "Senior Backend Engineer",
  summary: "Backend engineer with eight years in payments.",
  experience: [
    { company: "Northwind Payments", title: "Senior Backend Engineer", start_date: "2021-03", is_current: true, bullets: ["Led the settlement migration."] },
    { company: "Contoso Logistics", title: "Backend Engineer", start_date: "2017-06", end_date: "2021-02", is_current: false, bullets: [] },
  ],
  education: [{ institution: "University of Leeds", degree: "BSc", field_of_study: "Computer Science", start_date: "2012", end_date: "2015" }],
  skills: ["Java", "PostgreSQL"],
  projects: [],
  certifications: [],
};

type Outcome = { status: "PENDING" } | { status: "PARSED" } | { status: "FAILED"; error: string };

/** Answers content GETs with each outcome in turn (the last one repeats); saves go to the real core-api. */
async function stubParseResult(page: Page, ...outcomes: Outcome[]) {
  let polls = 0;
  await page.route(CONTENT_URL, async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    const outcome = outcomes[Math.min(polls++, outcomes.length - 1)];
    const resumeId = /resumes\/([^/]+)\/content/.exec(route.request().url())![1];
    await route.fulfill({
      json: {
        resumeId,
        versionNumber: 1,
        source: "UPLOAD",
        parseStatus: outcome.status,
        ...(outcome.status === "FAILED" && { parseError: outcome.error }),
        ...(outcome.status === "PARSED" && {
          content: parsed,
          warnings: [{ path: "experience[1].company", code: "not_in_source" }],
        }),
      },
    });
  });
}

test("a new user completes onboarding: upload CV → review parsed profile → preferences", async ({ page }) => {
  await signUpAndSignIn(page, "onboard");

  // Nobody reaches the app without a profile: sign-in lands in onboarding, and so does the dashboard.
  await expect(page).toHaveURL(/\/onboarding\/cv$/);
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/onboarding\/cv$/);

  // Step 1: upload. Parsing is still pending for a couple of polls, so the review screen must wait, not show a blank form.
  await stubParseResult(page, { status: "PENDING" }, { status: "PENDING" }, { status: "PARSED" });
  await page.getByLabel("CV file").setInputFiles(pdf);
  await page.getByRole("button", { name: "Upload CV" }).click();
  await expect(page).toHaveURL(/\/onboarding\/review\?resume=/);
  await expect(page.getByText(/Reading your CV/)).toBeVisible();
  await expect(page.getByLabel("Full name")).toHaveCount(0);

  // Step 2: the parsed profile appears, pre-filled, with the ungrounded employer flagged.
  await expect(page.getByLabel("Full name")).toHaveValue("Jordan Reyes");
  await expect(page.getByLabel("Headline")).toHaveValue("Senior Backend Engineer");
  const second = page.getByRole("group", { name: "Experience 2" });
  await expect(second.getByLabel("Company")).toHaveValue("Contoso Logistics");
  await expect(second.getByText(/couldn't find this employer/)).toBeVisible();

  // Correct something, then save.
  await fill(page.getByLabel("Headline"), "Staff Backend Engineer");
  await fill(page.getByLabel("Skills"), "Java, PostgreSQL, Kafka");
  await page.getByRole("button", { name: "Save and continue" }).click();
  await expect(page).toHaveURL(/\/onboarding\/preferences$/);

  // Step 3: preferences.
  await fill(page.getByLabel("Job titles"), "Staff Engineer\nPlatform Engineer");
  await page.getByLabel("Remote").check();
  await fill(page.getByLabel("Minimum yearly salary"), "120000");
  await fill(page.getByLabel("Currency"), "GBP");
  await page.getByRole("button", { name: "Finish" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();

  // Everything was really stored: drop the stub and read it back after a reload.
  await page.unroute(CONTENT_URL);
  await page.goto("/profile");
  await expect(page.getByLabel("Headline")).toHaveValue("Staff Backend Engineer");
  await expect(page.getByLabel("Skills")).toHaveValue("Java, PostgreSQL, Kafka");
  await expect(page.getByRole("group", { name: "Experience 1" }).getByLabel("Company")).toHaveValue("Northwind Payments");

  await page.getByRole("link", { name: "Preferences" }).click();
  await expect(page.getByLabel("Job titles")).toHaveValue("Staff Engineer\nPlatform Engineer");
  await expect(page.getByLabel("Remote")).toBeChecked();
  await expect(page.getByLabel("Minimum yearly salary")).toHaveValue("120000");
  await expect(page.getByLabel("Currency")).toHaveValue("GBP");

  // Resume list: the first CV is primary; a second one can take over.
  await page.getByRole("link", { name: "CVs" }).click();
  const list = page.getByRole("list", { name: "Your CVs" });
  await expect(list.getByRole("listitem")).toHaveCount(1);
  await expect(list.getByText("Primary")).toBeVisible();
  await page.getByLabel("CV file").setInputFiles({ ...pdf, name: "second-cv.pdf" });
  await page.getByRole("button", { name: "Upload CV" }).click();
  await expect(list.getByRole("listitem")).toHaveCount(2);
  await page.getByRole("button", { name: "Make second-cv primary" }).click();
  await expect(page.getByRole("button", { name: "Make jordan-reyes-cv primary" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Make second-cv primary" })).toHaveCount(0);
});

test("a CV that cannot be read does not block onboarding: fill the profile by hand", async ({ page }) => {
  await signUpAndSignIn(page, "manual");
  await expect(page).toHaveURL(/\/onboarding\/cv$/);

  await stubParseResult(page, { status: "FAILED", error: "no_extractable_text" });
  await page.getByLabel("CV file").setInputFiles(pdf);
  await page.getByRole("button", { name: "Upload CV" }).click();
  await expect(page).toHaveURL(/\/onboarding\/review\?resume=/);

  await expect(page.getByText(/couldn't find any text in that file/)).toBeVisible();
  await expect(page.getByLabel("Full name")).toHaveValue("");
  await fill(page.getByLabel("Full name"), "Ada Lovelace");

  // The server, not the browser, has the last word: a javascript: link is refused with a reason.
  await page.getByRole("button", { name: "Add link" }).click();
  const url = page.getByLabel("Link 1 URL");
  await url.evaluate((input: HTMLInputElement) => input.removeAttribute("type"));
  await fill(url, "javascript:alert(1)");
  await page.getByRole("button", { name: "Save and continue" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "couldn't save" })).toContainText("must be an http(s) URL");
  await expect(page).toHaveURL(/\/onboarding\/review/);
  await page.getByRole("button", { name: "Remove link 1" }).click();

  await page.getByRole("button", { name: "Add experience" }).click();
  const job = page.getByRole("group", { name: "Experience 1" });
  await fill(job.getByLabel("Company"), "Analytical Engines");
  await fill(job.getByLabel("Job title"), "Programmer");
  await page.getByRole("button", { name: "Save and continue" }).click();
  await expect(page).toHaveURL(/\/onboarding\/preferences$/);

  // Preferences may all be left empty: saving them is what finishes onboarding.
  await page.getByRole("button", { name: "Finish" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
});
