import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { CostDashboard } from "./cost-dashboard";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown, search: "" }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/admin/billing",
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));

const row = (over: Record<string, unknown>) => ({
  calls: 1,
  failedCalls: 0,
  inputTokens: 100,
  outputTokens: 20,
  costUsd: 0.001,
  ...over,
});

const report = {
  from: "2026-09-25",
  to: "2026-10-01",
  totals: row({ calls: 1234, failedCalls: 12, inputTokens: 2_500_000, outputTokens: 400_000, costUsd: 12.5 }),
  byFeature: [row({ feature: "parse_resume", costUsd: 12.4 }), row({ feature: "embed_job", costUsd: 0.000024 })],
  byDay: [row({ day: "2026-09-30", costUsd: 5 }), row({ day: "2026-10-01", costUsd: 7.5 })],
  byModel: [row({ model: "claude-haiku-4-5", costUsd: 12.4 }), row({ model: "voyage-4", costUsd: 0.1 })],
  byDayFeature: [row({ day: "2026-09-30", feature: "parse_resume" }), row({ day: "2026-10-01", feature: "embed_job" })],
};

function setup(routes: Parameters<typeof fakeApi>[0], search = "") {
  hoisted.search = search;
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<CostDashboard />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
  replace.mockClear();
});

describe("CostDashboard", () => {
  it("shows totals and the cost by day, feature, model and feature per day", async () => {
    setup({ "GET /admin/billing/costs": () => json(report) });

    const totals = await screen.findByRole("list", { name: "Totals for the range" }).catch(() => screen.findByLabelText("Totals for the range"));
    expect(within(totals).getByText("$12.50")).toBeInTheDocument();
    expect(within(totals).getByText("1,234")).toBeInTheDocument();
    expect(within(totals).getByText("2,500,000")).toBeInTheDocument();
    expect(screen.getByText("1,234 calls from 2026-09-25 to 2026-10-01")).toBeInTheDocument();

    const byFeature = screen.getByRole("table", { name: "Cost by feature" });
    const featureRows = within(byFeature).getAllByRole("row");
    expect(featureRows).toHaveLength(3);
    expect(within(featureRows[1]).getByText("parse_resume")).toBeInTheDocument();
    expect(within(featureRows[1]).getByText("$12.40")).toBeInTheDocument();
    expect(within(featureRows[2]).getByText("$0.000024")).toBeInTheDocument();

    const byDay = screen.getByRole("table", { name: "Cost by day" });
    expect(within(byDay).getAllByRole("row").map((r) => r.textContent)).toEqual([
      expect.stringContaining("Day"),
      expect.stringContaining("2026-09-30"),
      expect.stringContaining("2026-10-01"),
    ]);
    expect(within(screen.getByRole("table", { name: "Cost by model" })).getByText("voyage-4")).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Cost by feature per day" })).getAllByRole("row")).toHaveLength(3);
  });

  it("asks for the range in the URL and changes it through the URL", async () => {
    const user = userEvent.setup();
    const api = setup({ "GET /admin/billing/costs": () => json(report) }, "from=2026-09-20&to=2026-09-30");

    await screen.findByText(/calls from/);
    const request = api.callsTo("GET", "/admin/billing/costs")[0];
    expect(request).toBeDefined();
    const url = new URL(api.fetch.mock.calls[0][0].url);
    expect(url.searchParams.get("from")).toBe("2026-09-20");
    expect(url.searchParams.get("to")).toBe("2026-09-30");

    const from = screen.getByLabelText("From");
    expect(from).toHaveValue("2026-09-20");
    fireEvent.change(from, { target: { value: "2026-09-22" } });
    expect(replace).toHaveBeenLastCalledWith("/admin/billing?from=2026-09-22&to=2026-09-30");

    await user.click(screen.getByRole("button", { name: "Last 7 days" }));
    expect(replace).toHaveBeenLastCalledWith("/admin/billing");
  });

  it("ignores a malformed date in the URL and asks for the default range", async () => {
    const api = setup({ "GET /admin/billing/costs": () => json(report) }, "from=yesterday");

    await screen.findByText(/calls from/);
    const url = new URL(api.fetch.mock.calls[0][0].url);
    expect(url.searchParams.has("from")).toBe(false);
  });

  it("says so when there were no calls", async () => {
    setup({
      "GET /admin/billing/costs": () =>
        json({ from: "2026-09-25", to: "2026-10-01", totals: row({ calls: 0, costUsd: 0 }), byFeature: [], byDay: [], byModel: [], byDayFeature: [] }),
    });

    expect(await screen.findByText("No AI calls in this range")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("shows the server's reason for a bad range and can retry", async () => {
    const user = userEvent.setup();
    let ok = false;
    setup({
      "GET /admin/billing/costs": () =>
        ok ? json(report) : json({ status: 400, code: "invalid_range", detail: "The range must be at most 366 days." }, 400),
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("The range must be at most 366 days.");
    ok = true;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/calls from/)).toBeInTheDocument();
  });

  it("tells a signed-in user who is not an admin that the page is not for them", async () => {
    setup({ "GET /admin/billing/costs": () => json({ status: 403, code: "forbidden", detail: "Access denied." }, 403) });

    expect(await screen.findByRole("alert")).toHaveTextContent("Not allowed");
  });
});
