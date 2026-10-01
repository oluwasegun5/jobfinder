import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { Feed } from "./feed";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

const item = (id: string, title: string, extra: Record<string, unknown> = {}) => ({
  job: {
    id,
    title,
    company: { id: "c1", name: "Acme" },
    city: "Lagos",
    country: "NG",
    workMode: "REMOTE",
    status: "ACTIVE",
    saved: false,
    applied: false,
    summary: `About ${title}`,
  },
  matchScore: 82,
  scoreSource: "LLM_SCORED",
  feedScore: 82,
  adjustment: 0,
  reasons: [],
  strengths: ["Five years of Java"],
  gaps: ["No Kubernetes"],
  model: "test-model",
  ...extra,
});

const noContent = () => new Response(null, { status: 204 });

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<Feed />);
  return api;
}

const cardOf = (title: string) => screen.getByRole("heading", { name: title }).closest("li") as HTMLElement;

beforeEach(() => {
  hoisted.client = undefined;
});

describe("Feed", () => {
  it("lists matches with a text score badge and says who scored it", async () => {
    setup({
      "GET /feed": () =>
        json({
          items: [
            item(A, "Java Engineer"),
            item(B, "Data Analyst", { matchScore: 58, scoreSource: "STAGE2_ONLY", strengths: [], gaps: [], model: undefined }),
          ],
        }),
    });

    expect(await screen.findByRole("link", { name: "Java Engineer" })).toHaveAttribute("href", `/jobs/${A}`);
    const ai = within(cardOf("Java Engineer"));
    expect(ai.getByText("AI-scored")).toBeInTheDocument();
    expect(ai.getByText(/Match score/)).toBeInTheDocument();
    expect(ai.getByText("82", { exact: false })).toBeInTheDocument();
    const estimate = within(cardOf("Data Analyst"));
    expect(estimate.getByText("Estimated")).toBeInTheDocument();
    expect(estimate.getByText(/~58/)).toBeInTheDocument();
    expect(estimate.queryByText("AI-scored")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("2 matches, best first");
  });

  it("expands strengths and gaps on request and says an estimate is an estimate", async () => {
    const user = userEvent.setup();
    setup({
      "GET /feed": () =>
        json({
          items: [
            item(A, "Java Engineer"),
            item(B, "Data Analyst", {
              matchScore: 58,
              scoreSource: "STAGE2_ONLY",
              strengths: [],
              gaps: [],
              fallbackReason: "DAILY_CAP_REACHED",
            }),
          ],
        }),
    });
    await screen.findByRole("link", { name: "Java Engineer" });

    const toggle = within(cardOf("Java Engineer")).getByRole("button", { name: /Why this match/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Five years of Java")).not.toBeInTheDocument();
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Five years of Java")).toBeVisible();
    expect(screen.getByText("No Kubernetes")).toBeVisible();
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");

    await user.click(within(cardOf("Data Analyst")).getByRole("button", { name: /About this score/ }));
    expect(screen.getByText(/has not scored it yet/)).toBeVisible();
    expect(screen.getByText(/used today's AI allowance/)).toBeVisible();
  });

  it("explains why the feed moved a job", async () => {
    setup({
      "GET /feed": () =>
        json({
          items: [
            item(A, "Java Developer", {
              feedScore: 69,
              adjustment: -15,
              reasons: [{ code: "HIDDEN_SIMILAR_TITLE", points: -15, count: 1, example: "Java Engineer" }],
            }),
            item(B, "Data Analyst", { adjustment: 6, reasons: [{ code: "SAVED_SAME_COMPANY", points: 6, count: 1 }] }),
            item(C, "Gardener"),
          ],
        }),
    });
    await screen.findByRole("link", { name: "Java Developer" });

    expect(within(cardOf("Java Developer")).getByText("Ranked lower by 15 points: you hid a similar job (“Java Engineer”).")).toBeInTheDocument();
    expect(within(cardOf("Data Analyst")).getByText("Ranked higher by 6 points: you saved another job at this company.")).toBeInTheDocument();
    expect(within(cardOf("Gardener")).queryByText(/Ranked/)).not.toBeInTheDocument();
  });

  describe("actions", () => {
    it("hides at once, sends it, and undoes in order even before the hide has finished", async () => {
      const user = userEvent.setup();
      let release: () => void = () => {};
      const hideSent = new Promise<void>((resolve) => (release = resolve));
      const api = setup({
        "GET /feed": () => json({ items: [item(A, "Java Engineer"), item(B, "Data Analyst")] }),
        [`PUT /jobs/${A}/hide`]: async () => {
          await hideSent;
          return noContent();
        },
        [`DELETE /jobs/${A}/hide`]: noContent,
      });
      await screen.findByRole("link", { name: "Java Engineer" });

      await user.click(screen.getByRole("button", { name: "Hide Java Engineer" }));
      // optimistic: the card is already replaced, though the request has not finished
      expect(screen.getByText("Hidden: Java Engineer")).toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "Java Engineer" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Data Analyst" })).toBeInTheDocument();

      await user.click(screen.getByRole("button", { name: "Undo hiding Java Engineer" }));
      expect(await screen.findByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
      expect(api.callsTo("DELETE", `/jobs/${A}/hide`)).toHaveLength(0); // queued behind the hide
      release();

      await waitFor(() => expect(api.callsTo("DELETE", `/jobs/${A}/hide`)).toHaveLength(1));
      const order = api.calls.filter((c) => c.path === `/jobs/${A}/hide`).map((c) => c.method);
      expect(order).toEqual(["PUT", "DELETE"]);
    });

    it("puts a card back and says so when hiding fails", async () => {
      const user = userEvent.setup();
      setup({
        "GET /feed": () => json({ items: [item(A, "Java Engineer")] }),
        [`PUT /jobs/${A}/hide`]: () => json({ detail: "No such job.", code: "job_not_found" }, 404),
      });
      await screen.findByRole("link", { name: "Java Engineer" });

      await user.click(screen.getByRole("button", { name: "Hide Java Engineer" }));

      expect(await screen.findByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
      expect(screen.getByRole("alert")).toHaveTextContent("No such job.");
    });

    it("saves and unsaves with the button reflecting it at once", async () => {
      const user = userEvent.setup();
      const api = setup({
        "GET /feed": () => json({ items: [item(A, "Java Engineer")] }),
        [`PUT /jobs/${A}/save`]: noContent,
        [`DELETE /jobs/${A}/save`]: noContent,
      });
      await screen.findByRole("link", { name: "Java Engineer" });

      const save = screen.getByRole("button", { name: "Save Java Engineer" });
      expect(save).toHaveAttribute("aria-pressed", "false");
      await user.click(save);
      const saved = screen.getByRole("button", { name: "Unsave Java Engineer" });
      expect(saved).toHaveAttribute("aria-pressed", "true");
      await waitFor(() => expect(api.callsTo("PUT", `/jobs/${A}/save`)).toHaveLength(1));
      await user.click(saved);
      expect(screen.getByRole("button", { name: "Save Java Engineer" })).toHaveAttribute("aria-pressed", "false");
      await waitFor(() => expect(api.callsTo("DELETE", `/jobs/${A}/save`)).toHaveLength(1));
    });

    it("rolls a failed save back", async () => {
      const user = userEvent.setup();
      setup({
        "GET /feed": () => json({ items: [item(A, "Java Engineer")] }),
        [`PUT /jobs/${A}/save`]: () => json({}, 500),
      });
      await screen.findByRole("link", { name: "Java Engineer" });

      await user.click(screen.getByRole("button", { name: "Save Java Engineer" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("Could not update this job");
      expect(screen.getByRole("button", { name: "Save Java Engineer" })).toHaveAttribute("aria-pressed", "false");
    });

    it("marks a job as applied, shows an undo row, and can undo", async () => {
      const user = userEvent.setup();
      const api = setup({
        "GET /feed": () => json({ items: [item(A, "Java Engineer")] }),
        [`PUT /jobs/${A}/applied`]: noContent,
        [`DELETE /jobs/${A}/applied`]: noContent,
      });
      await screen.findByRole("link", { name: "Java Engineer" });

      await user.click(screen.getByRole("button", { name: "Mark Java Engineer as applied" }));
      expect(screen.getByText("Marked as applied: Java Engineer")).toBeInTheDocument();
      await waitFor(() => expect(api.callsTo("PUT", `/jobs/${A}/applied`)).toHaveLength(1));

      await user.click(screen.getByRole("button", { name: "Undo marking Java Engineer as applied" }));
      expect(await screen.findByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
      await waitFor(() => expect(api.callsTo("DELETE", `/jobs/${A}/applied`)).toHaveLength(1));
    });
  });

  describe("states", () => {
    it("shows a loading status first", () => {
      setup({ "GET /feed": () => new Promise<Response>(() => {}) });

      expect(screen.getByRole("status")).toHaveTextContent("Loading your matches");
    });

    it("shows an error with a retry that works", async () => {
      const user = userEvent.setup();
      const api = setup({ "GET /feed": () => json({ detail: "Try later." }, 503) });

      expect(await screen.findByText("Try later.")).toBeInTheDocument();
      api.routes["GET /feed"] = () => json({ items: [item(A, "Java Engineer")] });
      await user.click(screen.getByRole("button", { name: "Try again" }));

      expect(await screen.findByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
    });

    it("sends someone without a resume to upload one", async () => {
      setup({ "GET /feed": () => json({ items: [], emptyReason: "NO_RESUME" }) });

      expect(await screen.findByText("Upload your resume to see matches")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Upload a resume" })).toHaveAttribute("href", "/profile/resumes");
    });

    it("sends someone without preferences to set them", async () => {
      setup({ "GET /feed": () => json({ items: [], emptyReason: "NO_PREFERENCES" }) });

      expect(await screen.findByText("Set your job preferences")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Set preferences" })).toHaveAttribute("href", "/profile/preferences");
    });

    it("lets someone whose resume is still processing check again", async () => {
      const user = userEvent.setup();
      const api = setup({ "GET /feed": () => json({ items: [], emptyReason: "RESUME_PROCESSING" }) });
      expect(await screen.findByText("Your resume is still being analysed")).toBeInTheDocument();

      api.routes["GET /feed"] = () => json({ items: [item(A, "Java Engineer")] });
      await user.click(screen.getByRole("button", { name: "Check again" }));

      expect(await screen.findByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
    });

    it("says there are no matches yet and points to search and preferences", async () => {
      setup({ "GET /feed": () => json({ items: [], emptyReason: "NO_MATCHES" }) });

      expect(await screen.findByText("No matches yet")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Search all jobs" })).toHaveAttribute("href", "/jobs");
      expect(screen.getByRole("link", { name: "adjust your preferences" })).toHaveAttribute("href", "/profile/preferences");
    });

    it("loads more with the cursor the server gave and appends", async () => {
      const user = userEvent.setup();
      const urls: string[] = [];
      setup({
        "GET /feed": (request) => {
          urls.push(request.url);
          const cursor = new URL(request.url).searchParams.get("cursor");
          return cursor ? json({ items: [item(B, "Data Analyst")] }) : json({ items: [item(A, "Java Engineer")], nextCursor: "next-1" });
        },
      });
      await screen.findByRole("link", { name: "Java Engineer" });

      await user.click(screen.getByRole("button", { name: "Load more" }));

      expect(await screen.findByRole("link", { name: "Data Analyst" })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Java Engineer" })).toBeInTheDocument();
      expect(new URL(urls[1]).searchParams.get("cursor")).toBe("next-1");
      expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    });
  });
});
