import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { JobSearch } from "./job-search";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown, search: "", push: vi.fn() }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: hoisted.push }),
  usePathname: () => "/jobs",
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

const job = (id: string, title: string, extra: Record<string, unknown> = {}) => ({
  id,
  title,
  company: { id: "c1", name: "Acme" },
  city: "Lagos",
  country: "NG",
  workMode: "REMOTE",
  status: "ACTIVE",
  saved: false,
  ...extra,
});

function setup(routes: Parameters<typeof fakeApi>[0], search = "") {
  hoisted.search = search;
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<JobSearch />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
  hoisted.push.mockReset();
});

describe("JobSearch", () => {
  it("lists results and sends the URL's keyword and filters to the API", async () => {
    let url = "";
    setup(
      {
        "GET /jobs": (r) => {
          url = r.url;
          return json({ items: [job(A, "Java Engineer")] });
        },
      },
      "q=java&workMode=REMOTE&minSalary=50000&salaryCurrency=GBP",
    );
    const list = await screen.findByRole("list", { name: "Job results" });
    expect(within(list).getByRole("link", { name: "Java Engineer" })).toHaveAttribute("href", `/jobs/${A}`);
    expect(screen.getByRole("status")).toHaveTextContent("1 job shown");
    const query = new URL(url).searchParams;
    expect(query.get("q")).toBe("java");
    expect(query.getAll("workMode")).toEqual(["REMOTE"]);
    expect(query.get("minSalary")).toBe("50000");
    expect(query.get("salaryCurrency")).toBe("GBP");
  });

  it("writes a submitted keyword to the URL", async () => {
    const user = userEvent.setup();
    setup({ "GET /jobs": () => json({ items: [] }) });
    await screen.findByText("No jobs found");
    await user.type(screen.getByLabelText("Keywords"), "data analyst");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(hoisted.push).toHaveBeenCalledWith("/jobs?q=data+analyst");
  });

  it("applies filters from the panel to the URL", async () => {
    const user = userEvent.setup();
    setup({ "GET /jobs": () => json({ items: [] }) }, "q=java");
    await screen.findByText("No jobs found");
    await user.click(screen.getByRole("button", { name: "Filters" }));
    await user.click(screen.getByRole("checkbox", { name: "Remote" }));
    await user.click(screen.getByRole("checkbox", { name: "Senior" }));
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(hoisted.push).toHaveBeenCalledWith("/jobs?q=java&workMode=REMOTE&seniority=SENIOR");
  });

  it("loads the next page with the cursor", async () => {
    const user = userEvent.setup();
    const cursors: (string | null)[] = [];
    setup({
      "GET /jobs": (r) => {
        const cursor = new URL(r.url).searchParams.get("cursor");
        cursors.push(cursor);
        return cursor ? json({ items: [job(B, "Second job")] }) : json({ items: [job(A, "First job")], nextCursor: "next-1" });
      },
    });
    await screen.findByText("First job");
    await user.click(screen.getByRole("button", { name: "Load more" }));
    await screen.findByText("Second job");
    expect(cursors).toEqual([null, "next-1"]);
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("saves a job and shows it as saved", async () => {
    const user = userEvent.setup();
    const api = setup({
      "GET /jobs": () => json({ items: [job(A, "Java Engineer")] }),
      [`PUT /jobs/${A}/save`]: () => new Response(null, { status: 204 }),
    });
    const button = await screen.findByRole("button", { name: "Save Java Engineer" });
    expect(button).toHaveAttribute("aria-pressed", "false");
    await user.click(button);
    await waitFor(() => expect(screen.getByRole("button", { name: "Unsave Java Engineer" })).toHaveAttribute("aria-pressed", "true"));
    expect(api.callsTo("PUT", `/jobs/${A}/save`)).toHaveLength(1);
  });

  it("hides a job and can undo it", async () => {
    const user = userEvent.setup();
    setup({
      "GET /jobs": () => json({ items: [job(A, "Java Engineer")] }),
      [`PUT /jobs/${A}/hide`]: () => new Response(null, { status: 204 }),
      [`DELETE /jobs/${A}/hide`]: () => new Response(null, { status: 204 }),
    });
    await user.click(await screen.findByRole("button", { name: "Hide Java Engineer" }));
    expect(await screen.findByText("Hidden: Java Engineer")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Undo hiding Java Engineer" }));
    expect(await screen.findByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
  });

  it("shows the server's message when saving fails and keeps the job unsaved", async () => {
    const user = userEvent.setup();
    setup({
      "GET /jobs": () => json({ items: [job(A, "Java Engineer")] }),
      [`PUT /jobs/${A}/save`]: () => json({ detail: "That job is gone.", code: "job_not_found" }, 404),
    });
    await user.click(await screen.findByRole("button", { name: "Save Java Engineer" }));
    expect(await screen.findByText("That job is gone.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save Java Engineer" })).toHaveAttribute("aria-pressed", "false");
  });

  it("asks the similar-jobs endpoint and explains the list", async () => {
    let called = "";
    setup(
      {
        [`GET /jobs/${A}/similar`]: (r) => {
          called = r.url;
          return json({ items: [job(B, "Related job", { similarity: 0.87 })] });
        },
      },
      `similarTo=${A}`,
    );
    expect(await screen.findByText("Related job")).toBeInTheDocument();
    expect(screen.getByText("87% similar")).toBeInTheDocument();
    expect(screen.getByText(/similar to one you were looking at/)).toBeInTheDocument();
    expect(called).toContain(`/jobs/${A}/similar`);
    expect(screen.queryByRole("search")).not.toBeInTheDocument();
  });

  it("explains a search timeout and lets the person retry", async () => {
    const user = userEvent.setup();
    let attempts = 0;
    setup({
      "GET /jobs": () => {
        attempts += 1;
        return attempts === 1 ? json({ detail: "x", code: "search_timeout" }, 503) : json({ items: [job(A, "Java Engineer")] });
      },
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("took too long");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Java Engineer")).toBeInTheDocument();
  });
});

describe("JobSearch saved searches", () => {
  it("offers to save a search that has a keyword or filters", async () => {
    setup({ "GET /jobs": () => json({ items: [] }) }, "q=java&workMode=REMOTE");
    await screen.findByText("No jobs found");
    expect(screen.getByRole("button", { name: "Save this search" })).toBeEnabled();
  });

  it("will not save an empty search", async () => {
    setup({ "GET /jobs": () => json({ items: [] }) });
    await screen.findByText("No jobs found");
    expect(screen.getByRole("button", { name: "Save this search" })).toBeDisabled();
  });

  it("has no save action in the similar-jobs view", async () => {
    setup({ [`GET /jobs/${A}/similar`]: () => json({ items: [] }) }, `similarTo=${A}`);
    await screen.findByText("No jobs found");
    expect(screen.queryByRole("button", { name: "Save this search" })).not.toBeInTheDocument();
  });
});
