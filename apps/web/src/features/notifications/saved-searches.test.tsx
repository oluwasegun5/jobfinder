import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { SavedSearches } from "./saved-searches";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

const search = {
  id: A,
  name: "Go in Lagos",
  criteria: { q: "golang", workMode: ["REMOTE"], country: ["NG"], minSalary: 50000, salaryCurrency: "USD" },
  frequency: "DAILY",
  lastRunAt: "2026-10-01T08:00:00Z",
  createdAt: "2026-09-30T08:00:00Z",
};

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<SavedSearches />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("SavedSearches", () => {
  it("lists each search in words with a link to its results", async () => {
    setup({ "GET /saved-searches": () => json({ items: [search] }) });
    const list = await screen.findByRole("list", { name: "Saved searches" });
    expect(within(list).getByRole("heading", { name: "Go in Lagos" })).toBeInTheDocument();
    expect(within(list).getByText("golang, Remote, NG, from USD 50,000")).toBeInTheDocument();
    expect(within(list).getByRole("link", { name: "See results" })).toHaveAttribute(
      "href",
      "/jobs?q=golang&workMode=REMOTE&country=NG&minSalary=50000&salaryCurrency=USD",
    );
  });

  it("changes how often a search emails, keeping the rest as it was", async () => {
    const user = userEvent.setup();
    const api = setup({
      "GET /saved-searches": () => json({ items: [search] }),
      [`PUT /saved-searches/${A}`]: (r) => r.json().then((body) => json({ ...search, ...body })),
    });
    await user.selectOptions(await screen.findByLabelText('How often to email "Go in Lagos"'), "INSTANT");
    await vi.waitFor(() => expect(api.callsTo("PUT", `/saved-searches/${A}`)).toHaveLength(1));
    expect(api.callsTo("PUT", `/saved-searches/${A}`)[0].body).toEqual({
      name: "Go in Lagos",
      criteria: search.criteria,
      frequency: "INSTANT",
    });
  });

  it("deletes only after a confirmation", async () => {
    const user = userEvent.setup();
    let items = [search];
    const api = setup({
      "GET /saved-searches": () => json({ items }),
      [`DELETE /saved-searches/${A}`]: () => {
        items = [];
        return new Response(null, { status: 204 });
      },
    });
    await user.click(await screen.findByRole("button", { name: "Delete Go in Lagos" }));
    expect(api.callsTo("DELETE", `/saved-searches/${A}`)).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "Keep it" }));
    await user.click(screen.getByRole("button", { name: "Delete Go in Lagos" }));
    await user.click(screen.getByRole("button", { name: 'Delete "Go in Lagos"' }));
    expect(await screen.findByText("No saved searches")).toBeInTheDocument();
    expect(api.callsTo("DELETE", `/saved-searches/${A}`)).toHaveLength(1);
  });

  it("points to the job search when there are none", async () => {
    setup({ "GET /saved-searches": () => json({ items: [] }) });
    expect(await screen.findByText("No saved searches")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Find jobs" })).toHaveAttribute("href", "/jobs");
  });

  it("shows the server's reason when a change fails", async () => {
    const user = userEvent.setup();
    setup({
      "GET /saved-searches": () => json({ items: [search] }),
      [`PUT /saved-searches/${A}`]: () => json({ status: 404, code: "saved_search_not_found", detail: "No such saved search." }, 404),
    });
    await user.selectOptions(await screen.findByLabelText('How often to email "Go in Lagos"'), "WEEKLY");
    expect(await screen.findByRole("alert")).toHaveTextContent("No such saved search.");
  });
});
