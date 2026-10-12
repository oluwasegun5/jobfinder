import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { PricingView } from "./pricing-page";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

// Names, credits and prices here are test data: the page must show whatever the API says, nothing of its own.
const catalogue = {
  plans: [
    { code: "free", name: "Trial", monthlyCredits: 300, prices: [] },
    {
      code: "starter",
      name: "Basic",
      monthlyCredits: 2500,
      prices: [
        { provider: "STRIPE", currency: "USD", amountMinor: 500 },
        { provider: "PAYSTACK", currency: "NGN", amountMinor: 450000 },
      ],
    },
    { code: "pro", name: "Plus", monthlyCredits: 6000, prices: [{ provider: "STRIPE", currency: "USD", amountMinor: 1200 }] },
  ],
  packs: [
    { id: "pack_small", name: "Top-up", credits: 2000, prices: [{ provider: "PAYSTACK", currency: "NGN", amountMinor: 800000 }, { provider: "STRIPE", currency: "USD", amountMinor: 499 }] },
  ],
  rolloverCapCredits: 0,
};

function setup(props: { naira: boolean; signedIn?: boolean }, routes: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ "GET /billing/plans": () => json(catalogue), ...routes });
  hoisted.client = api.client;
  renderWithQueryClient(<PricingView naira={props.naira} signedIn={props.signedIn ?? false} />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("PricingView", () => {
  it("shows every offered plan and pack from the API in naira first for a Nigerian visitor", async () => {
    setup({ naira: true });

    const plans = await screen.findByRole("list", { name: "Plans" });
    const cards = within(plans).getAllByRole("listitem");
    expect(cards.map((card) => within(card).getByRole("heading").textContent)).toEqual(["Trial", "Basic", "Plus"]);
    expect(within(cards[1]).getByText("₦4,500")).toBeInTheDocument();
    expect(within(cards[1]).getByText(/2,500 credits a month/)).toBeInTheDocument();
    // Plus has no naira price: it says so instead of inventing one.
    expect(within(cards[2]).getByText("Not available in NGN")).toBeInTheDocument();
    expect(screen.getByText("₦8,000")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /NGN/ })).toBeChecked();
    expect(screen.getAllByText(/One free trial per person/).length).toBeGreaterThan(0);
  });

  it("switches currency with the keyboard and shows dollar prices through Stripe", async () => {
    const user = userEvent.setup();
    setup({ naira: true });

    const naira = await screen.findByRole("radio", { name: /NGN/ });
    naira.focus();
    await user.keyboard("{ArrowRight}");

    expect(screen.getByRole("radio", { name: /USD/ })).toBeChecked();
    expect(screen.getByText("$5")).toBeInTheDocument();
    expect(screen.getByText("$12")).toBeInTheDocument();
    expect(screen.getByText("$4.99")).toBeInTheDocument();
    expect(screen.getByText(/Paid through Stripe/)).toBeInTheDocument();
  });

  it("defaults to dollars elsewhere and sends visitors to sign up, members to billing", async () => {
    setup({ naira: false });

    expect(await screen.findByRole("radio", { name: /USD/ })).toBeChecked();
    expect(screen.getByRole("link", { name: /Start your free trial/ })).toHaveAttribute("href", "/signup");
    expect(screen.getByRole("link", { name: "Choose Basic" })).toHaveAttribute("href", "/signup");
  });

  it("links a signed-in member to the billing page to pay", async () => {
    setup({ naira: false, signedIn: true });

    expect(await screen.findByRole("link", { name: "Choose Basic" })).toHaveAttribute("href", "/billing");
  });

  it("explains a failure to load and retries", async () => {
    const user = userEvent.setup();
    const api = setup({ naira: false }, { "GET /billing/plans": () => json({ status: 500, detail: "x" }, 500) });

    await user.click(await screen.findByRole("button", { name: /Try again/ }));

    expect(api.callsTo("GET", "/billing/plans").length).toBeGreaterThanOrEqual(2);
  });
});
