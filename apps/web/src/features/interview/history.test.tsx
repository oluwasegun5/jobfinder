import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { InterviewHistory } from "./history";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const row = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  jobId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  jobTitle: "Backend Engineer",
  jobCompany: "Harbor Freight Tech",
  status: "COMPLETED",
  maxTurns: 5,
  turnsAnswered: 5,
  creditsConsumed: 15,
  overall: 4,
  createdAt: "2026-10-02T10:00:00Z",
  ...extra,
});

beforeEach(() => {
  hoisted.client = undefined;
});

describe("InterviewHistory", () => {
  it("lists interviews with status, progress, score and credits, each linking to its session", async () => {
    const api = fakeApi({
      "GET /interview-sessions": () =>
        json({
          items: [row("11111111-1111-4111-8111-111111111111"), row("22222222-2222-4222-8222-222222222222", { status: "ACTIVE", turnsAnswered: 2, overall: undefined, creditsConsumed: 7 })],
          page: 0,
          size: 10,
          totalElements: 2,
          totalPages: 1,
        }),
    });
    hoisted.client = api.client;
    renderWithQueryClient(<InterviewHistory />);

    const list = await screen.findByRole("list", { name: "Your interviews" });
    const [done, active] = within(list).getAllByRole("link");
    expect(done).toHaveAttribute("href", "/interviews/11111111-1111-4111-8111-111111111111");
    expect(done).toHaveTextContent("Completed");
    expect(done).toHaveTextContent("5 of 5 answered");
    expect(done).toHaveTextContent("Overall 4 / 5");
    expect(done).toHaveTextContent("15 credits");
    expect(active).toHaveTextContent("In progress");
    expect(active).toHaveTextContent("2 of 5 answered");
    expect(active).not.toHaveTextContent("Overall");
    expect(screen.queryByRole("navigation", { name: "Pages" })).not.toBeInTheDocument();
    expect(api.callsTo("GET", "/interview-sessions")[0]).toBeDefined();
  });

  it("pages through the history", async () => {
    const user = userEvent.setup();
    const api = fakeApi({
      "GET /interview-sessions": (request) => {
        const page = Number(new URL(request.url).searchParams.get("page") ?? 0);
        return json({ items: [row(`${page + 1}`.repeat(8) + "-1111-4111-8111-111111111111", { jobTitle: `Job on page ${page + 1}` })], page, size: 10, totalElements: 12, totalPages: 2 });
      },
    });
    hoisted.client = api.client;
    renderWithQueryClient(<InterviewHistory />);

    expect(await screen.findByText("Job on page 1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByText("Job on page 2")).toBeInTheDocument();
    expect(screen.getByText("Page 2 of 2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("explains an empty history and points to the jobs", async () => {
    const api = fakeApi({ "GET /interview-sessions": () => json({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }) });
    hoisted.client = api.client;
    renderWithQueryClient(<InterviewHistory />);

    expect(await screen.findByText(/have not practised an interview yet/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Find a job" })).toHaveAttribute("href", "/jobs");
  });

  it("says when the history cannot be loaded and lets the person retry", async () => {
    const api = fakeApi({ "GET /interview-sessions": () => json({ status: 503, code: "x" }, 503) });
    hoisted.client = api.client;
    renderWithQueryClient(<InterviewHistory />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/unavailable/);
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
