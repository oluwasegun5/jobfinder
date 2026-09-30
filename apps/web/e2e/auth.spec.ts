import { expect, test, type Locator } from "@playwright/test";

import { waitForEmailLink } from "./mailpit";

/**
 * Fills an input and confirms the value stuck. Under `next dev` the page can hydrate after the
 * first keystrokes, wiping them, so retry until the value survives.
 */
async function fill(input: Locator, value: string) {
  await expect(async () => {
    await input.fill(value);
    await expect(input).toHaveValue(value, { timeout: 500 });
  }).toPass();
}

const password = "correct-horse-battery";

// Synthetic address, unique per run so the suite can be re-run against a long-lived database.
const uniqueEmail = (tag: string) => `e2e-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.test`;

test("signup → verify email → login → protected page → logout", async ({ page }) => {
  const email = uniqueEmail("signup");

  // Protected pages bounce visitors to login and remember where they were headed.
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/login\?next=%2Fdashboard/);

  await page.getByRole("link", { name: "Create an account" }).click();
  await fill(page.getByLabel("Email"), email);
  await fill(page.getByLabel("Password", { exact: true }), password);
  await fill(page.getByLabel("Confirm password"), password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();

  // Signing in before verifying is refused with a way to get a new link.
  await page.goto("/login");
  await fill(page.getByLabel("Email"), email);
  await fill(page.getByLabel("Password"), password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("status")).toContainText("verify your email");

  // The link in the email points at WEB_BASE_URL; open it on the app under test.
  const link = await waitForEmailLink(email, "/verify-email");
  await page.goto(`${link.pathname}${link.search}`);
  await expect(page.getByText("Your email is verified")).toBeVisible();

  await page.getByRole("link", { name: "Go to sign in" }).click();
  await fill(page.getByLabel("Email"), email);
  await fill(page.getByLabel("Password"), password);
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();

  // Silent refresh: the access token is memory-only, so a reload proves the cookie restores the session.
  await page.reload();
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();

  await page.getByRole("button", { name: "Sign out" }).first().click();
  await expect(page).toHaveURL(/\/login$/);

  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/login\?next=%2Fdashboard/);
});

test("login shows a generic error for wrong credentials", async ({ page }) => {
  await page.goto("/login");
  await fill(page.getByLabel("Email"), uniqueEmail("nobody"));
  await fill(page.getByLabel("Password"), password);
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
});

test("forgot password → reset → login with the new password", async ({ page }) => {
  const email = uniqueEmail("reset");
  const newPassword = "another-long-passphrase";

  await page.goto("/signup");
  await fill(page.getByLabel("Email"), email);
  await fill(page.getByLabel("Password", { exact: true }), password);
  await fill(page.getByLabel("Confirm password"), password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();

  await page.goto("/forgot-password");
  await fill(page.getByLabel("Email"), email);
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText("a reset link is on its way")).toBeVisible();

  const link = await waitForEmailLink(email, "/reset-password");
  await page.goto(`${link.pathname}${link.search}`);
  await fill(page.getByLabel("New password", { exact: true }), newPassword);
  await fill(page.getByLabel("Confirm new password"), newPassword);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.getByText("Your password has been changed")).toBeVisible();

  await page.getByRole("link", { name: "Go to sign in" }).click();
  await fill(page.getByLabel("Email"), email);
  await fill(page.getByLabel("Password"), newPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
});
