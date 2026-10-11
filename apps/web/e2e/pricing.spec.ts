import { expect, test, type Page } from "@playwright/test";

/**
 * The public pricing page against a stand-in for core-api (G3, ADR 0036 addendum): no sign-in, every plan and pack the
 * API offers, naira first for a Nigerian browser and dollars elsewhere, a keyboard-operable currency switch, and the
 * one-free-trial rule. Like the other stubbed specs it needs no backend stack. Screenshots go to test-results.
 */
const CATALOGUE = {
  plans: [
    { code: "free", name: "Free", monthlyCredits: 300, prices: [] },
    {
      code: "starter",
      name: "Starter",
      monthlyCredits: 2500,
      prices: [
        { provider: "STRIPE", currency: "USD", amountMinor: 500 },
        { provider: "PAYSTACK", currency: "NGN", amountMinor: 450000 },
      ],
    },
    {
      code: "pro",
      name: "Pro",
      monthlyCredits: 6000,
      prices: [
        { provider: "STRIPE", currency: "USD", amountMinor: 1200 },
        { provider: "PAYSTACK", currency: "NGN", amountMinor: 1000000 },
      ],
    },
  ],
  packs: [
    { id: "pack_small", name: "Small credit pack", credits: 2000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 499 }, { provider: "PAYSTACK", currency: "NGN", amountMinor: 400000 }] },
    { id: "pack_large", name: "Large credit pack", credits: 10000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 1999 }, { provider: "PAYSTACK", currency: "NGN", amountMinor: 1500000 }] },
  ],
  rolloverCapCredits: 0,
};

async function stubApi(page: Page) {
  await page.route("**/api/core/**", (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api\/core/, "");
    const key = `${route.request().method()} ${path}`;
    if (key === "GET /billing/plans") return route.fulfill({ json: CATALOGUE });
    // Signed out: no refresh cookie, so the session refresh is refused.
    if (key === "POST /auth/refresh") return route.fulfill({ status: 401, json: { status: 401, code: "unauthorized" } });
    return route.fulfill({ status: 404, json: { status: 404, code: "e2e_unrouted", detail: key } });
  });
}

test.describe("from Nigeria", () => {
  test.use({ timezoneId: "Africa/Lagos", locale: "en-NG" });

  test("shows naira through Paystack first and every plan and pack from the API", async ({ page }, testInfo) => {
    await stubApi(page);
    await page.goto("/pricing");

    await expect(page.getByRole("heading", { name: "Pricing", level: 1 })).toBeVisible();
    await expect(page.getByRole("radio", { name: "NGN" })).toBeChecked();
    await expect(page.getByText("Prices in NGN. Paid through Paystack.")).toBeVisible();
    const plans = page.getByRole("list", { name: "Plans" });
    await expect(plans.getByRole("heading")).toHaveText(["Free", "Starter", "Pro"]);
    await expect(plans.getByText("₦4,500")).toBeVisible();
    await expect(plans.getByText("₦10,000")).toBeVisible();
    await expect(page.getByRole("list", { name: "Credit packs" }).getByRole("heading")).toHaveText(["Small credit pack", "Large credit pack"]);
    await expect(page.getByText("One free trial per person.").first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Start your free trial" })).toHaveAttribute("href", "/signup");

    await page.setViewportSize({ width: 1280, height: 900 });
    await page.screenshot({ path: testInfo.outputPath("pricing-desktop-ngn.png"), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: testInfo.outputPath("pricing-mobile-ngn.png"), fullPage: true });
    // Mobile first: nothing scrolls sideways.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test("switches to dollars with the keyboard", async ({ page }) => {
    await stubApi(page);
    await page.goto("/pricing");

    await page.getByRole("radio", { name: "NGN" }).focus();
    await page.keyboard.press("ArrowRight");

    await expect(page.getByRole("radio", { name: "USD" })).toBeChecked();
    await expect(page.getByText("Prices in USD. Paid through Stripe.")).toBeVisible();
    await expect(page.getByRole("list", { name: "Plans" }).getByText("$5", { exact: true })).toBeVisible();
    await page.getByRole("link", { name: "Choose Starter" }).focus();
    await expect(page.getByRole("link", { name: "Choose Starter" })).toBeFocused();
  });
});

test.describe("from elsewhere", () => {
  test.use({ timezoneId: "Europe/London", locale: "en-GB" });

  test("shows dollars through Stripe first, and the landing page links to it", async ({ page }, testInfo) => {
    await stubApi(page);
    await page.goto("/");
    await page.getByRole("navigation", { name: "Account" }).getByRole("link", { name: "Pricing" }).click();

    await expect(page).toHaveURL(/\/pricing$/);
    await expect(page.getByRole("radio", { name: "USD" })).toBeChecked();
    await expect(page.getByRole("list", { name: "Plans" }).getByText("$12", { exact: true })).toBeVisible();
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.screenshot({ path: testInfo.outputPath("pricing-desktop-usd.png"), fullPage: true });
  });
});
