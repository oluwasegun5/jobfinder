import type { Page } from "@playwright/test";

import { expect, GREENHOUSE_URL, LEVER_URL, test } from "./fixtures";
import { IDS, PDF_BYTES } from "./stub-core-api";

const panel = (page: Page) => page.locator("[data-jobfinder-panel]");

async function openPanel(page: Page): Promise<void> {
  await panel(page).getByRole("button", { name: "JobFinder", exact: true }).click();
}

async function fill(page: Page): Promise<void> {
  await openPanel(page);
  await panel(page).getByRole("button", { name: "Fill from JobFinder" }).click();
  await expect(panel(page).getByRole("heading", { name: /^Filled \(/ }), `panel said: ${await panel(page).locator(".status").innerText()}`).toBeVisible();
}

const submitCounts = (page: Page) => page.evaluate(() => ({ submits: (window as never as { __submitCount: number }).__submitCount, clicks: (window as never as { __submitClicks: number }).__submitClicks }));

const fileInfo = (page: Page, selector: string) =>
  page.evaluate((sel) => {
    const input = document.querySelector<HTMLInputElement>(sel);
    const file = input?.files?.[0];
    return file ? { name: file.name, size: file.size, type: file.type } : null;
  }, selector);

test.describe("Greenhouse", () => {
  test("does nothing until the user presses the button", async ({ stub, signIn, openAts }) => {
    await signIn();
    const page = await openAts(GREENHOUSE_URL, "greenhouse-form.html");
    await expect(panel(page)).toHaveCount(1);
    await page.waitForTimeout(1000);
    expect(await page.locator("#first_name").inputValue()).toBe("");
    expect(stub.count("GET", "/extension/apply-context")).toBe(0);
  });

  test("fills the form, uploads the CV, lists what it left, and never submits", async ({ stub, signIn, openAts }) => {
    await signIn();
    const page = await openAts(GREENHOUSE_URL, "greenhouse-form.html");
    await fill(page);

    await expect(page.locator("#first_name")).toHaveValue("Sam");
    await expect(page.locator("#last_name")).toHaveValue("Example");
    await expect(page.locator("#email")).toHaveValue("sam.example@example.test");
    await expect(page.locator("#phone")).toHaveValue("+1 555 0100");
    await expect(page.locator("#question_1001")).toHaveValue("https://www.linkedin.com/in/sam-example");
    await expect(page.locator("#question_1003")).toHaveValue("https://github.com/sam-example");
    await expect(page.locator("#question_1004")).toHaveValue("I like building reliable services for people.");
    await expect(page.locator("#cover_letter_text")).toHaveValue(/^Dear Example Corp team,/);

    // Filled fields are highlighted.
    for (const id of ["#first_name", "#email", "#question_1004"]) {
      await expect(page.locator(id)).toHaveAttribute("data-jobfinder-filled", /.*/);
    }

    // The approved tailored resume's ATS file is attached, from the service worker's bytes.
    expect(await fileInfo(page, "#resume")).toEqual({ name: "tailored-cv.pdf", size: PDF_BYTES.length, type: "application/pdf" });

    // Sensitive, hidden, captcha and unmatched fields stay untouched and are listed for the user.
    for (const id of ["#question_1007", "#question_1008", "#job_application_gender", "#job_application_race", "#website_url_hp", "#captcha_answer", "#question_1006"]) {
      await expect(page.locator(id)).not.toHaveAttribute("data-jobfinder-filled", /.*/);
    }
    expect(await page.locator("#question_1007").inputValue()).toBe("");
    expect(await page.locator("#website_url_hp").inputValue()).toBe("");
    expect(await page.locator("#captcha_answer").inputValue()).toBe("");
    const needs = await panel(page).locator("ul").nth(1).innerText();
    expect(needs).toMatch(/authorized to work/i);
    expect(needs).toMatch(/sponsorship/i);
    expect(needs).toMatch(/gender/i);

    // The form was never submitted, and the submit button was never clicked.
    expect(await submitCounts(page)).toEqual({ submits: 0, clicks: 0 });
    expect(stub.count("POST", "/applications")).toBe(0);
    // Only the page URL went to core-api as a query.
    expect(stub.find("GET", "/extension/apply-context")[0]?.query).toEqual({ url: GREENHOUSE_URL });
  });

  test("logs the application only after the user confirms, and is idempotent", async ({ stub, signIn, openAts }) => {
    await signIn();
    const page = await openAts(GREENHOUSE_URL, "greenhouse-form.html");
    await fill(page);
    expect(stub.count("POST", "/applications")).toBe(0);

    await panel(page).getByRole("button", { name: "I submitted this application" }).click();
    await expect(panel(page).getByText("Logged in your JobFinder tracker as applied.")).toBeVisible();
    const created = stub.find("POST", "/applications");
    expect(created).toHaveLength(1);
    expect(created[0]?.body).toMatchObject({ jobId: IDS.jobGreenhouse });
    expect(stub.applications.get(IDS.applicationNew)?.status).toBe("APPLIED");
    expect(await submitCounts(page)).toEqual({ submits: 0, clicks: 0 });
  });

  test("undo restores the form, and typed values are kept unless the user overwrites", async ({ signIn, openAts }) => {
    await signIn();
    const page = await openAts(GREENHOUSE_URL, "greenhouse-form.html");
    await page.locator("#phone").fill("0000");
    await fill(page);
    await expect(page.locator("#phone")).toHaveValue("0000");
    await expect(page.locator("#first_name")).toHaveValue("Sam");

    await panel(page).getByRole("button", { name: /^Overwrite 1 field/ }).click();
    await expect(page.locator("#phone")).toHaveValue("+1 555 0100");

    await panel(page).getByRole("button", { name: "Undo fill" }).click();
    await expect(page.locator("#first_name")).toHaveValue("");
    await expect(page.locator("#phone")).toHaveValue("0000");
    await expect(page.locator("#first_name")).not.toHaveAttribute("data-jobfinder-filled", /.*/);
    expect(await fileInfo(page, "#resume")).toBeNull();
  });
});

test.describe("Lever", () => {
  test("fills the form from the primary resume and answers, and never submits", async ({ stub, signIn, openAts }) => {
    await signIn();
    const page = await openAts(LEVER_URL, "lever-form.html");
    await fill(page);

    await expect(page.locator("input[name=name]")).toHaveValue("Sam Example");
    await expect(page.locator("input[name=email]")).toHaveValue("sam.example@example.test");
    await expect(page.locator("input[name=phone]")).toHaveValue("+1 555 0100");
    await expect(page.locator("input[name='urls[LinkedIn]']")).toHaveValue("https://www.linkedin.com/in/sam-example");
    await expect(page.locator("input[name='urls[GitHub]']")).toHaveValue("https://github.com/sam-example");
    await expect(page.locator("input[name='urls[Other]']")).toHaveValue("");
    await expect(page.locator("input[name='cards[8c1f2a60-0000-4000-8000-000000000002][field0]']")).toHaveValue("I am looking for 90,000 USD a year.");
    await expect(page.locator("input[name='cards[8c1f2a60-0000-4000-8000-000000000003][field0]']")).toHaveValue("Four weeks.");

    // No approved tailored resume in this pack, so the primary resume is the CV.
    expect(await fileInfo(page, "#resume-upload-input")).toMatchObject({ size: PDF_BYTES.length, type: "application/pdf" });
    expect(stub.count("GET", `/resumes/${IDS.resume}/download-url`)).toBe(1);

    // EEO, work authorization and captcha are left for the user.
    expect(await page.locator("select[name='eeo[gender]']").inputValue()).toBe("");
    expect(await page.locator("select[name='cards[8c1f2a60-0000-4000-8000-000000000005][field0]']").inputValue()).toBe("");
    expect(await page.locator("input[name=captcha_text]").inputValue()).toBe("");
    const needs = await panel(page).locator("ul").nth(1).innerText();
    expect(needs).toMatch(/authorized to work/i);
    expect(needs).toMatch(/gender/i);

    expect(await submitCounts(page)).toEqual({ submits: 0, clicks: 0 });
  });

  test("moves an existing saved application to applied", async ({ stub, signIn, openAts }) => {
    await signIn();
    const page = await openAts(LEVER_URL, "lever-form.html");
    await fill(page);
    await panel(page).getByRole("button", { name: "I submitted this application" }).click();
    await expect(panel(page).getByText("Logged in your JobFinder tracker as applied.")).toBeVisible();

    expect(stub.count("POST", "/applications")).toBe(0);
    const moved = stub.find("POST", `/applications/${IDS.applicationLever}/status`);
    expect(moved).toHaveLength(1);
    expect(moved[0]?.body).toMatchObject({ status: "APPLIED" });
    expect(stub.applications.get(IDS.applicationLever)?.status).toBe("APPLIED");
  });
});

test.describe("Session", () => {
  test("silently refreshes through the httpOnly cookie when the access token is gone", async ({ stub, worker, signIn, openAts }) => {
    await signIn();
    expect(stub.count("POST", "/auth/refresh")).toBe(0);

    // Drop the access token the way a service-worker restart after expiry would.
    await worker.evaluate(async () => {
      await chrome.storage.session.clear();
    });

    const page = await openAts(GREENHOUSE_URL, "greenhouse-form.html");
    await fill(page);
    await expect(page.locator("#first_name")).toHaveValue("Sam");
    const refreshes = stub.find("POST", "/auth/refresh");
    expect(refreshes).toHaveLength(1);
    expect(refreshes[0]?.cookie).toContain("refresh_token=synthetic-refresh");
  });

  test("falls back to sign-in when there is no refresh cookie", async ({ context, stub, worker, signIn, openAts }) => {
    await signIn();
    await context.clearCookies();
    await worker.evaluate(async () => {
      await chrome.storage.session.clear();
    });
    const page = await openAts(GREENHOUSE_URL, "greenhouse-form.html");
    await openPanel(page);
    await panel(page).getByRole("button", { name: "Fill from JobFinder" }).click();
    await expect(panel(page).locator(".status")).toContainText(/sign in/i);
    expect(await page.locator("#first_name").inputValue()).toBe("");
    expect(stub.count("GET", "/extension/apply-context")).toBe(0);
  });

  test("the access token is readable only from trusted extension contexts", async ({ context, extensionId, signIn, openAts }) => {
    await signIn();
    const page = await openAts(GREENHOUSE_URL, "greenhouse-form.html");
    await fill(page);

    // From the content script's world, chrome.storage.session must refuse (TRUSTED_CONTEXTS).
    const client = await context.newCDPSession(page);
    const worlds: number[] = [];
    client.on("Runtime.executionContextCreated", (e: { context: { id: number; origin: string } }) => {
      if (e.context.origin === `chrome-extension://${extensionId}`) worlds.push(e.context.id);
    });
    await client.send("Runtime.disable");
    await client.send("Runtime.enable");
    await expect.poll(() => worlds.length).toBeGreaterThan(0);
    const contentWorld = worlds[worlds.length - 1]!;
    const result = await client.send("Runtime.evaluate", {
      contextId: contentWorld,
      awaitPromise: true,
      returnByValue: true,
      expression: `chrome.storage.session.get("auth").then(() => "readable", (e) => "refused: " + e.message)`,
    });
    expect(String((result.result as { value?: unknown }).value)).toMatch(/^refused: /);
  });

  test("sign out clears the session and calls logout", async ({ context, extensionId, stub, worker, signIn }) => {
    await signIn();
    const popup = await context.newPage();
    await popup.goto(`chrome-extension://${extensionId}/popup.html`);
    await popup.locator("#sign-out").click();
    await expect(popup.locator("#login")).toBeVisible();
    expect(stub.count("POST", "/auth/logout")).toBe(1);
    expect(await worker.evaluate(async () => Object.keys(await chrome.storage.session.get(null)).length)).toBe(0);
  });
});
