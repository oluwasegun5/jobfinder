import { expect, test, type Page, type Route } from "@playwright/test";

/**
 * The billing page against a stand-in for core-api (P6.1, ADR 0036): the upgrade checkout redirect, cancelling a
 * subscription, the checkout return and cancel pages, the credit history and the 402 upgrade prompt on an AI action.
 *
 * Like the other stubbed specs this needs no backend stack, and above all no payment provider: the stand-in plays
 * core-api, and the "hosted checkout" it redirects to is a page of this same app, so the test proves the browser follows
 * the URL it is given and loads nothing from a provider. core-api's own tests cover the server side (signed webhooks,
 * idempotency, the ledger).
 */
const PRO = { code: "pro", name: "Pro", monthlyCredits: 6000 };

type State = { subscribed: boolean; canceled: boolean; balance: number; requests: string[] };

function backend(options: { subscribed?: boolean; balance?: number } = {}) {
  const state: State = { subscribed: options.subscribed ?? false, canceled: false, balance: options.balance ?? 300, requests: [] };

  const me = () => ({
    plan: state.subscribed ? PRO : { code: "free", name: "Free", monthlyCredits: 300 },
    status: state.subscribed ? "ACTIVE" : "FREE",
    subscription: state.subscribed
      ? { provider: "STRIPE", status: "ACTIVE", currentPeriodEnd: "2026-11-03T10:00:00Z", cancelAtPeriodEnd: state.canceled }
      : undefined,
    balance: state.balance,
    grantedThisPeriod: state.subscribed ? 6000 : 300,
    usedThisPeriod: 40,
    allowance: { dailyCap: 500, used: 40, remaining: 460, resetsAt: "2026-10-05T00:00:00Z" },
  });

  async function handle(route: Route) {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^\/api\/core/, "");
    const key = `${request.method()} ${path}`;
    const ok = (json: unknown, status = 200) => route.fulfill({ status, json });
    const body = request.postDataJSON?.() as Record<string, unknown> | undefined;
    state.requests.push(key);

    if (key === "POST /auth/refresh") return ok({ accessToken: "e2e-token", expiresIn: 900 });
    if (key === "GET /auth/me") return ok({ id: "u1", email: "e2e@example.test", role: "USER", emailVerified: true, aiConsent: true });
    if (key === "GET /profile") return ok({ onboardingCompleted: true });
    if (key === "GET /billing/plans") {
      return ok({
        plans: [
          { code: "free", name: "Free", monthlyCredits: 300, prices: [] },
          { ...PRO, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 900 }, { provider: "PAYSTACK", currency: "NGN", amountMinor: 1500000 }] },
        ],
        packs: [{ id: "small", name: "Small pack", credits: 1000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 500 }] }],
        rolloverCapCredits: 0,
      });
    }
    if (key === "GET /billing/me") return ok(me());
    if (key === "GET /billing/ledger") {
      return ok({
        items: [
          { id: 3, createdAt: "2026-10-03T10:00:00Z", delta: -4, reason: "AI_USAGE", balanceAfter: 296, feature: "tailor_cv" },
          { id: 2, createdAt: "2026-10-01T10:00:00Z", delta: 300, reason: "PLAN_GRANT", balanceAfter: 300 },
        ],
      });
    }
    if (key === "POST /billing/checkout") {
      if (body?.plan === "pro" && body?.provider === "STRIPE") {
        // The "hosted page" is local: nothing leaves the app.
        return ok({ url: `${new URL(request.url()).origin}/billing/return?stub=1` });
      }
      return ok({ status: 400, code: "provider_not_offered", detail: "provider_not_offered" }, 400);
    }
    if (key === "POST /billing/subscription/cancel") {
      state.canceled = true;
      return ok(me());
    }
    if (key === "POST /interview-sessions") {
      return route.fulfill({
        status: 402,
        contentType: "application/problem+json",
        json: { type: "urn:jobfinder:problem:insufficient-credits", status: 402, code: "insufficient_credits", detail: "no credits", balance: 0 },
      });
    }
    if (key.startsWith("GET /jobs/")) {
      return ok({ id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", title: "Java Engineer", company: { id: "c1", name: "Acme" }, status: "ACTIVE", description: "Build things.", skills: ["Java"], applyUrl: "https://acme.example/apply" });
    }
    return route.fulfill({ status: 404, json: { status: 404, code: "e2e_unrouted", detail: key } });
  }

  return { handle, state };
}

async function openApp(page: Page, options?: Parameters<typeof backend>[0]) {
  const api = backend(options);
  await page.route("**/api/core/**", (route) => api.handle(route));
  return api;
}

test("the billing page shows the plan, the balance and the history, and upgrading follows the hosted checkout URL", async ({ page }) => {
  const api = await openApp(page);
  await page.goto("/billing");

  await expect(page.getByRole("heading", { name: "Billing" })).toBeVisible();
  await expect(page.getByText("Your plan: Free")).toBeVisible();
  await expect(page.getByTestId("balance")).toHaveText("300");
  const history = page.getByLabel("Credit history");
  await expect(history.getByText("Plan credits")).toBeVisible();
  await expect(history.getByText("AI usage")).toBeVisible();
  await expect(history.getByText("balance 296")).toBeVisible();

  // Each provider that is switched on is offered with its own currency; a pack is offered too.
  await expect(page.getByRole("button", { name: /Upgrade for .*9\.00 a month with Stripe/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Upgrade for .*15,000\.00 a month with Paystack/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Buy for .*5\.00 with Stripe/ })).toBeVisible();

  await page.getByRole("button", { name: /Upgrade for .*9\.00 a month with Stripe/ }).click();
  await expect(page).toHaveURL(/\/billing\/return\?stub=1$/);
  await expect(page.getByRole("heading", { name: "Checkout" })).toBeVisible();
  expect(api.state.requests).toContain("POST /billing/checkout");
});

test("cancelling a subscription asks first, keeps the plan until the period ends and is shown afterwards", async ({ page }) => {
  const api = await openApp(page, { subscribed: true, balance: 5200 });
  await page.goto("/billing");

  await expect(page.getByText("Your plan: Pro")).toBeVisible();
  await expect(page.getByTestId("balance")).toHaveText("5,200");
  // A subscriber is not offered the upgrade again.
  await expect(page.getByRole("heading", { name: "Upgrade" })).toHaveCount(0);

  await page.getByRole("button", { name: "Cancel subscription" }).click();
  await expect(page.getByText(/Your plan stays until/)).toBeVisible();
  expect(api.state.canceled).toBe(false);
  await page.getByRole("button", { name: "Confirm cancel" }).click();

  await expect(page.getByText(/^Ends /)).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel subscription" })).toHaveCount(0);
  expect(api.state.canceled).toBe(true);
});

test("the checkout return and cancel pages say what happened without claiming a payment they cannot see", async ({ page }) => {
  await openApp(page, { subscribed: true, balance: 6000 });

  await page.goto("/billing/return");
  await expect(page.getByRole("status").filter({ hasText: "Thank you. You are on Pro" })).toBeVisible();

  await page.goto("/billing/return?canceled=1");
  await expect(page.getByText("The checkout was cancelled and you have not been charged.")).toBeVisible();
  await page.getByRole("link", { name: "Back to billing" }).click();
  await expect(page.getByRole("heading", { name: "Billing" })).toBeVisible();
});

test("an AI action refused with 402 shows the upgrade prompt that leads to billing", async ({ page }) => {
  await openApp(page, { balance: 0 });
  await page.goto("/jobs/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  await page.getByRole("link", { name: "Practise the interview" }).click();
  await page.getByRole("button", { name: /Start/ }).click();

  const prompt = page.locator("[role=alert]").filter({ hasText: "You are out of AI credits" });
  await expect(prompt).toBeVisible();
  await prompt.getByRole("link", { name: "Upgrade or add credits" }).click();
  await expect(page).toHaveURL(/\/billing$/);
});
