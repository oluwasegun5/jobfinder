import { expect, test } from "@playwright/test";

import { signUpAndSignIn } from "./support";

/**
 * The trial credit progress in the onboarding stepper (docs/adr/0046): a new user who has verified their email has
 * unlocked the first milestone and sees the other three, with the credits each releases, from the real billing status.
 * The denied and on-hold wording is checked against a stubbed status, since provoking those states needs a duplicate
 * identity.
 */
const ME_URL = /\/api\/core\/billing\/me$/;

test("a new user sees the first trial milestone unlocked and the others still to come", async ({ page }) => {
  await signUpAndSignIn(page, "trial-progress");
  await expect(page).toHaveURL(/\/onboarding\/cv$/);

  const progress = page.getByRole("region", { name: "Trial credits" });
  await expect(progress).toBeVisible();
  const milestones = progress.getByRole("list", { name: "Trial credit milestones" }).getByRole("listitem");
  await expect(milestones).toHaveCount(4);
  await expect(milestones.nth(0)).toContainText("Email verified");
  await expect(milestones.nth(0)).toContainText("Unlocked");
  for (const index of [1, 2, 3]) {
    await expect(milestones.nth(index)).toContainText("Not yet unlocked");
    await expect(milestones.nth(index)).toContainText(/\d+ credits/);
  }
  await expect(progress.getByText(/^30 of 300 trial credits unlocked$/)).toBeVisible();

  // Next to the stepper, readable without colour and reachable by landmark.
  await expect(page.getByRole("navigation", { name: "Onboarding progress" })).toBeVisible();
});

test("a denied trial is told nothing was added, with no reason and no promised credits", async ({ page }) => {
  await page.route(ME_URL, async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({
      response,
      json: { ...body, trial: { state: "DENIED", unlocked: 0, remaining: 0, milestones: [], tranches: [] } },
    });
  });
  await signUpAndSignIn(page, "trial-denied");

  const progress = page.getByRole("region", { name: "Trial credits" });
  await expect(progress.getByText(/aren.t available for this account/)).toBeVisible();
  await expect(progress.getByRole("list")).toHaveCount(0);
  await expect(progress).not.toContainText(/duplicate|disposable|already used/i);
});

test("a trial on hold shows only what is unlocked", async ({ page }) => {
  await page.route(ME_URL, async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({
      response,
      json: {
        ...body,
        trial: {
          state: "HOLD",
          unlocked: 30,
          remaining: 30,
          milestones: ["EMAIL_VERIFIED"],
          tranches: [
            { milestone: "EMAIL_VERIFIED", code: "M1", credits: 30, unlocked: true },
            { milestone: "CV_PARSED", code: "M2", credits: 70, unlocked: false },
            { milestone: "ONBOARDING_COMPLETED", code: "M3", credits: 100, unlocked: false },
            { milestone: "MATCHES_VIEWED", code: "M4", credits: 100, unlocked: false },
          ],
        },
      },
    });
  });
  await signUpAndSignIn(page, "trial-hold");

  const progress = page.getByRole("region", { name: "Trial credits" });
  await expect(progress.getByText(/being confirmed/)).toBeVisible();
  await expect(progress.getByRole("listitem")).toHaveCount(1);
  await expect(progress.getByText("30 trial credits unlocked")).toBeVisible();
});
