import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { TrialProgress } from "./trial-progress";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const tranche = (code: string, milestone: string, credits: number, unlocked: boolean) => ({ code, milestone, credits, unlocked });
const tranches = (unlocked: number) => [
  tranche("M1", "EMAIL_VERIFIED", 30, unlocked >= 1),
  tranche("M2", "CV_PARSED", 70, unlocked >= 2),
  tranche("M3", "ONBOARDING_COMPLETED", 100, unlocked >= 3),
  tranche("M4", "MATCHES_VIEWED", 100, unlocked >= 4),
];

const me = (trial: unknown) => ({
  plan: { code: "free", name: "Free", monthlyCredits: 0 },
  status: "FREE",
  balance: 30,
  grantedThisPeriod: 0,
  usedThisPeriod: 0,
  trial,
});

function setup(trial: unknown) {
  hoisted.client = fakeApi({ "GET /billing/me": () => json(me(trial)) }).client;
  renderWithQueryClient(<TrialProgress />);
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("TrialProgress", () => {
  it("lists the four milestones, which are unlocked, what each releases and the total unlocked", async () => {
    setup({ state: "GRANTED", unlocked: 100, remaining: 100, milestones: ["EMAIL_VERIFIED", "CV_PARSED"], tranches: tranches(2) });

    const list = await screen.findByRole("list", { name: "Trial credit milestones" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(4);
    expect(items[0]).toHaveTextContent("Email verified");
    expect(items[0]).toHaveTextContent("30 credits");
    expect(items[0]).toHaveTextContent("Unlocked");
    expect(items[1]).toHaveTextContent("CV read");
    expect(items[1]).toHaveTextContent("70 credits");
    expect(items[1]).toHaveTextContent("Unlocked");
    expect(items[2]).toHaveTextContent("Profile and preferences saved");
    expect(items[2]).toHaveTextContent("Not yet unlocked");
    expect(items[3]).toHaveTextContent("Matches viewed");
    expect(items[3]).toHaveTextContent("100 credits");
    expect(items[3]).toHaveTextContent("Not yet unlocked");
    expect(screen.getByText("100 of 300 trial credits unlocked")).toBeInTheDocument();
  });

  it("states the total from the API's own amounts, not a fixed number", async () => {
    setup({
      state: "GRANTED",
      unlocked: 0,
      remaining: 0,
      milestones: [],
      tranches: [tranche("M1", "EMAIL_VERIFIED", 10, false), tranche("M2", "CV_PARSED", 5, false), tranche("M3", "ONBOARDING_COMPLETED", 5, false), tranche("M4", "MATCHES_VIEWED", 0, false)],
    });

    expect(await screen.findByText("0 of 20 trial credits unlocked")).toBeInTheDocument();
  });

  it("says the matches step needs a later day", async () => {
    setup({ state: "GRANTED", unlocked: 30, remaining: 30, milestones: ["EMAIL_VERIFIED"], tranches: tranches(1) });

    const list = await screen.findByRole("list", { name: "Trial credit milestones" });
    expect(within(list).getAllByRole("listitem")[3]).toHaveTextContent("on a later day");
  });

  it("before the first milestone, shows what each releases without claiming any is unlocked", async () => {
    setup({ state: "PENDING", unlocked: 0, remaining: 0, milestones: [], tranches: tranches(0) });

    expect(await screen.findByText("0 of 300 trial credits unlocked")).toBeInTheDocument();
    expect(screen.queryByText("Unlocked")).not.toBeInTheDocument();
  });

  it("for a trial on hold, shows only what is unlocked and says the rest is being confirmed", async () => {
    setup({ state: "HOLD", unlocked: 30, remaining: 30, milestones: ["EMAIL_VERIFIED"], tranches: tranches(1) });

    expect(await screen.findByText(/being confirmed/)).toBeInTheDocument();
    const items = within(screen.getByRole("list", { name: "Trial credit milestones" })).getAllByRole("listitem");
    expect(items).toHaveLength(1);
    expect(items[0]).toHaveTextContent("Email verified");
    expect(screen.getByText("30 trial credits unlocked")).toBeInTheDocument();
    expect(screen.queryByText(/300/)).not.toBeInTheDocument();
  });

  it("for a denied trial, promises nothing and does not say why", async () => {
    setup({ state: "DENIED", unlocked: 0, remaining: 0, milestones: [], tranches: [] });

    expect(await screen.findByText(/aren't available for this account/)).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/email|duplicate|match|CV|disposable/i);
  });

  it("shows nothing for an account from before the trial, while loading, or when the status cannot be read", async () => {
    setup(null);
    await vi.waitFor(() => expect(screen.queryByRole("region", { name: "Trial credits" })).not.toBeInTheDocument());
  });

  it("shows nothing when the status request fails", async () => {
    hoisted.client = fakeApi({ "GET /billing/me": () => json({ code: "boom" }, 500) }).client;
    renderWithQueryClient(<TrialProgress />);

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole("region", { name: "Trial credits" })).not.toBeInTheDocument();
  });
});
