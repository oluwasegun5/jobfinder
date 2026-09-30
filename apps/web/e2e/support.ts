import { expect, type Locator, type Page } from "@playwright/test";

import { waitForEmailLink } from "./mailpit";

export const password = "correct-horse-battery";

// Synthetic address, unique per run so the suite can be re-run against a long-lived database.
export const uniqueEmail = (tag: string) =>
  `e2e-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.test`;

/**
 * Fills an input and confirms the value stuck. Under `next dev` the page can hydrate after the
 * first keystrokes, wiping them, so retry until the value survives.
 */
export async function fill(input: Locator, value: string) {
  await expect(async () => {
    await input.fill(value);
    await expect(input).toHaveValue(value, { timeout: 500 });
  }).toPass();
}

/** Signs up a brand-new user, verifies the email through Mailpit and signs in. */
export async function signUpAndSignIn(page: Page, tag: string) {
  const email = uniqueEmail(tag);

  await page.goto("/signup");
  await fill(page.getByLabel("Email"), email);
  await fill(page.getByLabel("Password", { exact: true }), password);
  await fill(page.getByLabel("Confirm password"), password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();

  const link = await waitForEmailLink(email, "/verify-email");
  await page.goto(`${link.pathname}${link.search}`);
  await expect(page.getByText("Your email is verified")).toBeVisible();

  await page.getByRole("link", { name: "Go to sign in" }).click();
  await fill(page.getByLabel("Email"), email);
  await fill(page.getByLabel("Password"), password);
  await page.getByRole("button", { name: "Sign in" }).click();
  return email;
}
