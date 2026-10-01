import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { SourceDashboard } from "./source-dashboard";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const ago = (ms: number) => new Date(Date.now() - ms).toISOString();

const greenhouse = {
  code: "GREENHOUSE",
  kind: "ATS",
  enabled: true,
  schedule: "SCHEDULED",
  health: "DEGRADED",
  lastRunAt: ago(3_600_000),
  nextDueAt: new Date(Date.now() + 3 * 3_600_000).toISOString(),
  running: false,
  enabledTargets: 12,
  totalTargets: 14,
  lastRun: {
    id: "r1",
    source: "GREENHOUSE",
    status: "PARTIAL",
    startedAt: ago(3_600_000),
    finishedAt: ago(3_590_000),
    targets: 12,
    fetched: 340,
    created: 21,
    updated: 300,
    expired: 4,
    errors: 3,
    errorSummary: "acme: SourceFetchException: board not found (HTTP 404)",
  },
  alerts: [{ rule: "ERROR_RATE", since: ago(7_200_000), occurrences: 2, detail: "3 of 12 target(s) failed (25%; the limit is 20%)." }],
};

const adzuna = {
  code: "ADZUNA",
  kind: "AGGREGATOR",
  enabled: true,
  schedule: "UNAVAILABLE",
  unavailableReason: "Adzuna credentials are not configured (ADZUNA_APP_ID, ADZUNA_APP_KEY)",
  health: "UNKNOWN",
  running: false,
  enabledTargets: 0,
  totalTargets: 0,
  alerts: [],
};

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<SourceDashboard />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("SourceDashboard", () => {
  it("shows each source with its health, last run, counts and open alerts", async () => {
    setup({ "GET /admin/ingestion/sources": () => json({ items: [greenhouse, adzuna] }) });

    const card = await screen.findByRole("listitem", { name: "GREENHOUSE" });
    expect(within(card).getByText("Degraded")).toBeInTheDocument();
    expect(within(card).getByText("Scheduled")).toBeInTheDocument();
    expect(within(card).getByText("Partial")).toBeInTheDocument();
    expect(within(card).getByText("340")).toBeInTheDocument();
    expect(within(card).getByText("3 of 12")).toBeInTheDocument();
    expect(within(card).getByText("12 of 14")).toBeInTheDocument();
    const alerts = within(card).getByRole("list", { name: "Open alerts for GREENHOUSE" });
    expect(within(alerts).getByText("High error rate")).toBeInTheDocument();
    expect(alerts).toHaveTextContent("3 of 12 target(s) failed");
    expect(within(card).getByText("What failed")).toBeInTheDocument();
    expect(within(card).getByText(/in 2 h|in 3 h/)).toBeInTheDocument();
  });

  it("explains a source that cannot run and does not offer to run it", async () => {
    setup({ "GET /admin/ingestion/sources": () => json({ items: [adzuna] }) });

    const card = await screen.findByRole("listitem", { name: "ADZUNA" });
    expect(within(card).getByText("Needs an API key")).toBeInTheDocument();
    expect(within(card).getByText(/credentials are not configured/)).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Run now: ADZUNA" })).toBeDisabled();
    expect(within(card).getByText("Not run yet. 0 of 0 targets on.")).toBeInTheDocument();
  });

  it("switches a source off and says what that means", async () => {
    const user = userEvent.setup();
    const api = setup({
      "GET /admin/ingestion/sources": () => json({ items: [greenhouse] }),
      "PUT /admin/ingestion/sources/GREENHOUSE/enabled": () =>
        json({ ...greenhouse, enabled: false, schedule: "DISABLED", nextDueAt: undefined }),
    });

    const toggle = await screen.findByRole("switch", { name: "Scheduled runs for GREENHOUSE" });
    expect(toggle).toBeChecked();
    await user.click(toggle);

    await vi.waitFor(() => expect(screen.getByRole("switch", { name: "Scheduled runs for GREENHOUSE" })).not.toBeChecked());
    expect(api.callsTo("PUT", "/admin/ingestion/sources/GREENHOUSE/enabled")[0].body).toEqual({ enabled: false });
    expect(screen.getByText("Disabled")).toBeInTheDocument();
    expect(screen.getByText(/off the schedule. A run already in progress will finish/)).toBeInTheDocument();
    expect(screen.getByText("Next scheduled run:", { exact: false })).toHaveTextContent("none");
  });

  it("starts a run, says so, and shows it running", async () => {
    const user = userEvent.setup();
    let running = false;
    const api = setup({
      "GET /admin/ingestion/sources": () => json({ items: [{ ...greenhouse, running }] }),
      "POST /admin/ingestion/sources/GREENHOUSE/runs": () => {
        running = true;
        return json({ source: "GREENHOUSE", status: "STARTED" }, 202);
      },
    });

    await user.click(await screen.findByRole("button", { name: "Run now: GREENHOUSE" }));

    expect(await screen.findByText("Run of GREENHOUSE started. This page updates when it finishes.")).toBeInTheDocument();
    expect(api.callsTo("POST", "/admin/ingestion/sources/GREENHOUSE/runs")).toHaveLength(1);
    await vi.waitFor(() => expect(screen.getByRole("button", { name: "Run now: GREENHOUSE" })).toBeDisabled());
    expect(screen.getByText("Running now")).toBeInTheDocument();
  });

  it("reports a run that is already in progress", async () => {
    const user = userEvent.setup();
    setup({
      "GET /admin/ingestion/sources": () => json({ items: [greenhouse] }),
      "POST /admin/ingestion/sources/GREENHOUSE/runs": () =>
        json({ status: 409, code: "run_in_progress", detail: "A run of this source is already in progress." }, 409),
    });

    await user.click(await screen.findByRole("button", { name: "Run now: GREENHOUSE" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("GREENHOUSE is already running.");
    expect(screen.getByRole("button", { name: "Run now: GREENHOUSE" })).toBeEnabled();
  });

  it("tells a non-admin they are not allowed instead of showing sources", async () => {
    setup({ "GET /admin/ingestion/sources": () => json({ status: 403, code: "forbidden", detail: "Access denied." }, 403) });

    expect(await screen.findByRole("alert")).toHaveTextContent("Not allowed");
    expect(screen.queryByRole("list", { name: "Sources" })).not.toBeInTheDocument();
  });

  it("offers a retry when the list cannot be loaded", async () => {
    const user = userEvent.setup();
    let fail = true;
    setup({
      "GET /admin/ingestion/sources": () =>
        fail ? json({ status: 500, detail: "An unexpected error occurred." }, 500) : json({ items: [greenhouse] }),
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("An unexpected error occurred.");
    fail = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("listitem", { name: "GREENHOUSE" })).toBeInTheDocument();
  });
});
