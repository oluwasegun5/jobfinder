import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { BillingPage, formatCredits, formatPrice } from "./billing-page";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const catalogue = {
  plans: [
    { code: "free", name: "Free", monthlyCredits: 300, prices: [] },
    { code: "pro", name: "Pro", monthlyCredits: 6000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 900 }] },
  ],
  packs: [{ id: "small", name: "Small pack", credits: 1000, prices: [{ provider: "PAYSTACK", currency: "NGN", amountMinor: 500000 }] }],
  rolloverCapCredits: 0,
};

const free = { plan: { code: "free", name: "Free", monthlyCredits: 300 }, status: "FREE", balance: 296, grantedThisPeriod: 300, usedThisPeriod: 4 };
const pro = {
  plan: { code: "pro", name: "Pro", monthlyCredits: 6000 },
  status: "ACTIVE",
  subscription: { provider: "STRIPE", status: "ACTIVE", currentPeriodEnd: "2026-11-03T10:00:00Z", cancelAtPeriodEnd: false },
  balance: 5000,
  grantedThisPeriod: 6000,
  usedThisPeriod: 1000,
};
const ledger = {
  items: [
    { id: 2, createdAt: "2026-10-03T10:00:00Z", delta: -4, reason: "AI_USAGE", balanceAfter: 296 },
    { id: 1, createdAt: "2026-10-01T10:00:00Z", delta: 300, reason: "PLAN_GRANT", balanceAfter: 300 },
  ],
};

function setup(me: unknown, routes: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    "GET /billing/plans": () => json(catalogue),
    "GET /billing/me": () => json(me),
    "GET /billing/ledger": () => json(ledger),
    ...routes,
  });
  hoisted.client = api.client;
  renderWithQueryClient(<BillingPage />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("formatting", () => {
  it("shows credits without noise and prices in their own currency", () => {
    expect(formatCredits("1234.50")).toBe("1,234.5");
    expect(formatCredits(undefined)).toBe("0");
    expect(formatPrice({ provider: "STRIPE", currency: "USD", amountMinor: 900 })).toMatch(/9\.00/);
  });
});

describe("BillingPage", () => {
  it("shows the plan, the balance and the history, and offers the upgrade and the pack", async () => {
    setup(free);

    expect(await screen.findByText("Your plan: Free")).toBeInTheDocument();
    expect(screen.getByTestId("balance")).toHaveTextContent("296");
    const history = await screen.findByLabelText("Credit history");
    expect(within(history).getByText("Plan credits")).toBeInTheDocument();
    expect(within(history).getByText("AI usage")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Upgrade for .*9\.00 a month with Stripe/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Buy for .* with Paystack/ })).toBeInTheDocument();
  });

  it("asks the API for a checkout and sends nothing but the choice", async () => {
    const user = userEvent.setup();
    const assign = vi.fn();
    Object.defineProperty(window, "location", { value: { ...window.location, assign }, writable: true });
    const api = setup(free, { "POST /billing/checkout": () => json({ url: "https://checkout.example/session" }) });

    await user.click(await screen.findByRole("button", { name: /Upgrade for/ }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("https://checkout.example/session"));
    expect(api.callsTo("POST", "/billing/checkout")[0].body).toEqual({ plan: "pro", provider: "STRIPE" });
  });

  it("explains a refused checkout in words", async () => {
    const user = userEvent.setup();
    setup(free, { "POST /billing/checkout": () => json({ status: 409, code: "already_subscribed", detail: "x" }, 409) });

    await user.click(await screen.findByRole("button", { name: /Upgrade for/ }));

    expect(await screen.findByText("You already have a paid plan.")).toBeInTheDocument();
  });

  it("lets a subscriber cancel only after confirming, and does not offer the upgrade", async () => {
    const user = userEvent.setup();
    const api = setup(pro, { "POST /billing/subscription/cancel": () => json({ ...pro, subscription: { ...pro.subscription, cancelAtPeriodEnd: true } }) });

    expect(await screen.findByText("Your plan: Pro")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Upgrade" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel subscription" }));
    expect(api.callsTo("POST", "/billing/subscription/cancel")).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "Confirm cancel" }));

    await waitFor(() => expect(api.callsTo("POST", "/billing/subscription/cancel")).toHaveLength(1));
  });
});
