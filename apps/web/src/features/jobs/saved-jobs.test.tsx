import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { SavedJobs } from "./saved-jobs";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<SavedJobs />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("SavedJobs", () => {
  it("lists saved jobs and removes one", async () => {
    const user = userEvent.setup();
    const api = setup({
      "GET /saved-jobs": () => json({ items: [{ id: A, title: "Java Engineer", company: { id: "c", name: "Acme" } }] }),
      [`DELETE /jobs/${A}/save`]: () => new Response(null, { status: 204 }),
    });
    expect(await screen.findByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove Java Engineer from saved jobs" }));
    await vi.waitFor(() => expect(screen.queryByRole("link", { name: "Java Engineer" })).not.toBeInTheDocument());
    expect(api.callsTo("DELETE", `/jobs/${A}/save`)).toHaveLength(1);
  });

  it("points to the search when nothing is saved", async () => {
    setup({ "GET /saved-jobs": () => json({ items: [] }) });
    expect(await screen.findByText("No saved jobs")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Find jobs" })).toHaveAttribute("href", "/jobs");
  });
});
