import { fireEvent, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { ActivationDashboard, formatRate } from "./activation-dashboard";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown, search: "" }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/admin/activation",
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));

const step = (name: string, count: number, fromPrevious?: number, overall?: number) => ({ step: name, count, fromPrevious, overall });

const report = {
  from: "2026-09-13",
  to: "2026-10-12",
  steps: [
    step("SIGNUP", 200, undefined, 1),
    step("EMAIL_VERIFIED", 150, 0.75, 0.75),
    step("CV_UPLOADED", 90, 0.6, 0.45),
    step("CV_PARSED", 80, 0.8889, 0.4),
    step("ONBOARDING_COMPLETED", 60, 0.75, 0.3),
    step("FIRST_MATCHES_VIEWED", 30, 0.5, 0.15),
    step("FIRST_TAILORED_CV", 6, 0.2, 0.03),
  ],
  cohorts: [
    { weekStart: "2026-09-07", counts: [120, 90, 50, 45, 30, 15, 3] },
    { weekStart: "2026-09-14", counts: [80, 60, 40, 35, 30, 15, 3] },
  ],
  trial: { granted: 140, hold: 4, denied: 6 },
};

function setup(routes: Parameters<typeof fakeApi>[0], search = "") {
  hoisted.search = search;
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<ActivationDashboard />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
  replace.mockClear();
});

describe("formatRate", () => {
  it("shows a ratio as a percentage with one decimal and a dash for none", () => {
    expect(formatRate(0.6667)).toBe("66.7%");
    expect(formatRate(1)).toBe("100%");
    expect(formatRate(0.03)).toBe("3%");
    expect(formatRate(undefined)).toBe("–");
    expect(formatRate(null)).toBe("–");
  });
});

describe("ActivationDashboard", () => {
  it("shows every step with its users, step-to-step and overall conversion", async () => {
    setup({ "GET /admin/activation": () => json(report) });

    const table = await screen.findByRole("table", { name: "Users per activation step with conversion" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(8);
    expect(rows[1]).toHaveTextContent("Signed up");
    expect(rows[1]).toHaveTextContent("200");
    expect(rows[1]).toHaveTextContent("100%");
    expect(rows[3]).toHaveTextContent("CV uploaded");
    expect(rows[3]).toHaveTextContent("90");
    expect(rows[3]).toHaveTextContent("60%");
    expect(rows[3]).toHaveTextContent("45%");
    expect(rows[4]).toHaveTextContent("88.9%");
    expect(rows[7]).toHaveTextContent("First tailored CV");
    expect(rows[7]).toHaveTextContent("20%");
    expect(rows[7]).toHaveTextContent("3%");
    expect(screen.getByText("200 signups from 2026-09-13 to 2026-10-12")).toBeInTheDocument();
  });

  it("shows the trial outcomes as counts", async () => {
    setup({ "GET /admin/activation": () => json(report) });

    const outcomes = await screen.findByLabelText("Trial outcome counts");
    expect(within(outcomes).getByText("Granted").nextSibling).toHaveTextContent("140");
    expect(within(outcomes).getByText("On hold").nextSibling).toHaveTextContent("4");
    expect(within(outcomes).getByText("Denied").nextSibling).toHaveTextContent("6");
  });

  it("shows the same counts per signup week", async () => {
    setup({ "GET /admin/activation": () => json(report) });

    const table = await screen.findByRole("table", { name: "Users per activation step, by the week they signed up" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("2026-09-07");
    expect(rows[1]).toHaveTextContent("120");
    expect(rows[2]).toHaveTextContent("2026-09-14");
  });

  it("asks for the range in the URL and changes it from the date fields", async () => {
    const api = setup({ "GET /admin/activation": () => json(report) }, "from=2026-09-20&to=2026-09-30");

    await screen.findByRole("table", { name: "Users per activation step with conversion" });
    expect(api.callsTo("GET", "/admin/activation")).toHaveLength(1);
    const url = new URL(api.fetch.mock.calls[0][0].url);
    expect(url.searchParams.get("from")).toBe("2026-09-20");
    expect(url.searchParams.get("to")).toBe("2026-09-30");

    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-09-22" } });
    expect(replace).toHaveBeenLastCalledWith("/admin/activation?from=2026-09-22&to=2026-09-30");
    fireEvent.click(screen.getByRole("button", { name: "Last 30 days" }));
    expect(replace).toHaveBeenLastCalledWith("/admin/activation");
  });

  it("says when nobody signed up in the range", async () => {
    setup({ "GET /admin/activation": () => json({ ...report, steps: report.steps.map((s) => ({ ...s, count: 0 })), cohorts: [] }) });

    expect(await screen.findByText("No signups in this range")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("tells a signed-in user who is not an admin that the page is not for them", async () => {
    setup({ "GET /admin/activation": () => json({ code: "forbidden", title: "Forbidden", status: 403 }, 403) });
    expect(await screen.findByRole("alert")).toHaveTextContent("Not allowed");
  });

  it("offers a retry when the funnel cannot be loaded", async () => {
    setup({ "GET /admin/activation": () => json({ code: "boom", title: "Server error", status: 500 }, 500) });

    expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
