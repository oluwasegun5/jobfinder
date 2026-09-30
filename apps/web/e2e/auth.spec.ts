import { expect, test } from "@playwright/test";

import { waitForEmailLink } from "./mailpit";
import { fill, password, uniqueEmail } from "./support";

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

  // A new user has no profile yet, so the app sends them into onboarding (see onboarding.spec.ts).
  await expect(page).toHaveURL(/\/onboarding\/cv$/);
  await expect(page.getByRole("heading", { name: "Upload your CV" })).toBeVisible();

  // Silent refresh: the access token is memory-only, so a reload proves the cookie restores the session.
  await page.reload();
  await expect(page.getByRole("heading", { name: "Upload your CV" })).toBeVisible();

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
  await expect(page).toHaveURL(/\/onboarding\/cv$/);
});
