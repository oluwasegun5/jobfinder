import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { RunHistory } from "./run-history";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown, search: "" }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/admin/ingestion/runs",
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));

const run = (n: number, over: Record<string, unknown> = {}) => ({
  id: `run-${n}`,
  source: "LEVER",
  status: "SUCCEEDED",
  startedAt: "2026-10-01T10:00:00Z",
  finishedAt: "2026-10-01T10:00:12Z",
  targets: 5,
  fetched: 50,
  created: 5,
  updated: 45,
  expired: 1,
  errors: 0,
  ...over,
});

const sources = { items: [{ code: "GREENHOUSE" }, { code: "LEVER" }] };

function setup(routes: Parameters<typeof fakeApi>[0], search = "") {
  hoisted.search = search;
  const api = fakeApi({ "GET /admin/ingestion/sources": () => json(sources), ...routes });
  hoisted.client = api.client;
  renderWithQueryClient(<RunHistory />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
  replace.mockClear();
});

describe("RunHistory", () => {
  it("lists runs with their counts and the reason a run went wrong", async () => {
    setup({
      "GET /admin/ingestion/runs": () =>
        json({
          items: [
            run(1),
            run(2, { status: "FAILED", errors: 5, fetched: 0, errorSummary: "acme: board not found (HTTP 404)" }),
          ],
          page: 0,
          size: 20,
          totalElements: 2,
          totalPages: 1,
        }),
    });

    const table = await screen.findByRole("table", { name: "Ingestion runs, newest first" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByText("Succeeded")).toBeInTheDocument();
    expect(within(rows[1]).getByText("12 s")).toBeInTheDocument();
    expect(within(rows[2]).getByText("Failed")).toBeInTheDocument();
    expect(within(rows[2]).getByText(/board not found/)).toBeInTheDocument();
    expect(screen.getByText("2 runs, page 1 of 1")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Run history pages" })).not.toBeInTheDocument();
  });

  it("pages through the history through the URL", async () => {
    const user = userEvent.setup();
    setup({
      "GET /admin/ingestion/runs": () => json({ items: [run(1)], page: 0, size: 20, totalElements: 45, totalPages: 3 }),
    });

    expect(await screen.findByText("45 runs, page 1 of 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Next" }));

    expect(replace).toHaveBeenCalledWith("/admin/ingestion/runs?page=2");
  });

  it("reads the page and the source from the URL and asks the server for them", async () => {
    const api = setup(
      { "GET /admin/ingestion/runs": () => json({ items: [run(1)], page: 1, size: 20, totalElements: 45, totalPages: 3 }) },
      "source=LEVER&page=2",
    );

    expect(await screen.findByText("45 runs, page 2 of 3")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Source" })).toHaveValue("LEVER");
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
    // the server is asked for exactly that page of that source (zero-based page 1, 20 a page)
    const asked = new URL(api.fetch.mock.calls.map(([r]) => (r as Request).url).find((u) => u.includes("/runs"))!);
    expect(asked.searchParams.get("source")).toBe("LEVER");
    expect(asked.searchParams.get("page")).toBe("1");
    expect(asked.searchParams.get("size")).toBe("20");
  });

  it("filters by source and goes back to the first page", async () => {
    const user = userEvent.setup();
    setup(
      { "GET /admin/ingestion/runs": () => json({ items: [run(1)], page: 2, size: 20, totalElements: 45, totalPages: 3 }) },
      "page=3",
    );

    await screen.findByText("45 runs, page 3 of 3");
    await user.selectOptions(screen.getByRole("combobox", { name: "Source" }), "GREENHOUSE");

    expect(replace).toHaveBeenCalledWith("/admin/ingestion/runs?source=GREENHOUSE");
  });

  it("says when there are no runs", async () => {
    setup({ "GET /admin/ingestion/runs": () => json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) });

    expect(await screen.findByText("No runs yet")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("tells a non-admin they are not allowed", async () => {
    setup({ "GET /admin/ingestion/runs": () => json({ status: 403, code: "forbidden", detail: "Access denied." }, 403) });

    expect(await screen.findByRole("alert")).toHaveTextContent("Not allowed");
  });
});
