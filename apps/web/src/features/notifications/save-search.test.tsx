import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { NO_FILTERS, type JobFilters } from "@/features/jobs/search-params";
import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { criteriaAreEmpty, describeCriteria, filtersToCriteria } from "./criteria";
import { SaveSearch } from "./save-search";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

function setup(filters: JobFilters, routes: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<SaveSearch filters={filters} />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("SaveSearch", () => {
  it("cannot save a search that would match every job", () => {
    setup(NO_FILTERS);
    expect(screen.getByRole("button", { name: "Save this search" })).toBeDisabled();
    expect(screen.getByText("Add a keyword or a filter to save a search.")).toBeInTheDocument();
  });

  it("saves the keyword and filters with the chosen frequency and a default name", async () => {
    const user = userEvent.setup();
    const api = setup(
      { ...NO_FILTERS, q: "golang", workMode: ["REMOTE"], country: "NG", minSalary: "50000", salaryCurrency: "GBP" },
      { "POST /saved-searches": (r) => r.json().then((body) => json({ id: "x", ...body }, 201)) },
    );
    await user.click(screen.getByRole("button", { name: "Save this search" }));
    await user.selectOptions(screen.getByLabelText("Email me"), "WEEKLY");
    await user.click(screen.getByRole("button", { name: "Save search" }));

    expect(await screen.findByText(/Saved "golang, Remote, NG, from GBP 50,000"/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Manage saved searches" })).toHaveAttribute("href", "/saved-searches");
    expect(api.callsTo("POST", "/saved-searches")[0].body).toEqual({
      name: "golang, Remote, NG, from GBP 50,000",
      criteria: { q: "golang", workMode: ["REMOTE"], country: ["NG"], minSalary: 50000, salaryCurrency: "GBP" },
      frequency: "WEEKLY",
    });
  });

  it("uses the name the person typed and says what is not saved", async () => {
    const user = userEvent.setup();
    const api = setup(
      { ...NO_FILTERS, q: "rust", postedWithinDays: "7" },
      { "POST /saved-searches": (r) => r.json().then((body) => json({ id: "x", ...body }, 201)) },
    );
    await user.click(screen.getByRole("button", { name: "Save this search" }));
    expect(screen.getByText(/posting window and the company filter are not saved/)).toBeInTheDocument();
    await user.type(screen.getByLabelText("Name"), "Rust roles");
    await user.click(screen.getByRole("button", { name: "Save search" }));
    await screen.findByText(/Saved "Rust roles"/);
    expect(api.callsTo("POST", "/saved-searches")[0].body).toMatchObject({ name: "Rust roles", frequency: "DAILY" });
  });

  it("shows the server's reason when the limit is reached", async () => {
    const user = userEvent.setup();
    setup(
      { ...NO_FILTERS, q: "java" },
      {
        "POST /saved-searches": () =>
          json({ status: 409, code: "saved_search_limit", detail: "You can keep up to 20 saved searches. Delete one first." }, 409),
      },
    );
    await user.click(screen.getByRole("button", { name: "Save this search" }));
    await user.click(screen.getByRole("button", { name: "Save search" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You can keep up to 20 saved searches.");
  });
});

describe("criteria helpers", () => {
  it("leaves out what a saved search does not keep", () => {
    const criteria = filtersToCriteria({ ...NO_FILTERS, q: "java", postedWithinDays: "7", companyId: "c", minSalary: "" });
    expect(criteria).toEqual({
      q: "java",
      workMode: undefined,
      employmentType: undefined,
      seniority: undefined,
      country: undefined,
      location: undefined,
      minSalary: undefined,
      salaryCurrency: undefined,
    });
    expect(criteriaAreEmpty(filtersToCriteria({ ...NO_FILTERS, postedWithinDays: "7", companyId: "c" }))).toBe(true);
    expect(describeCriteria(criteria)).toBe("java");
  });
});
