import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { JobDetailView } from "./job-detail";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

const detail = (extra: Record<string, unknown> = {}) => ({
  id: A,
  title: "Java Engineer",
  company: { id: "c1", name: "Acme" },
  city: "Lagos",
  country: "NG",
  workMode: "REMOTE",
  status: "ACTIVE",
  description: "Line one\n<script>alert(1)</script>\nLine three",
  skills: ["Java", "SQL"],
  applyUrl: "https://acme.example/apply",
  saved: false,
  hidden: false,
  similarAvailable: true,
  listings: [
    {
      source: "Remotive",
      url: "https://remotive.example/job/1",
      attribution: { name: "Remotive", text: "Jobs via", url: "https://remotive.example" },
    },
  ],
  ...extra,
});

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<JobDetailView id={A} />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("JobDetailView", () => {
  it("shows the job, the apply link, skills and source attribution", async () => {
    setup({ [`GET /jobs/${A}`]: () => json(detail()) });
    expect(await screen.findByRole("heading", { name: "Java Engineer" })).toBeInTheDocument();
    const apply = screen.getByRole("link", { name: /Apply/ });
    expect(apply).toHaveAttribute("href", "https://acme.example/apply");
    expect(apply).toHaveAttribute("rel", expect.stringContaining("noopener"));
    expect(screen.getByText("Java")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Remotive" })).toHaveLength(2);
    expect(screen.getByRole("link", { name: "Similar jobs" })).toHaveAttribute("href", `/jobs?similarTo=${A}`);
  });

  it("renders the description as text, never as HTML", async () => {
    setup({ [`GET /jobs/${A}`]: () => json(detail()) });
    const text = await screen.findByText(/Line one/);
    expect(text.textContent).toContain("<script>alert(1)</script>");
    expect(text.querySelector("script")).toBeNull();
  });

  it("does not render an unsafe apply link", async () => {
    setup({ [`GET /jobs/${A}`]: () => json(detail({ applyUrl: "javascript:alert(1)" })) });
    await screen.findByRole("heading", { name: "Java Engineer" });
    expect(screen.queryByRole("link", { name: /Apply/ })).not.toBeInTheDocument();
  });

  it("offers similar jobs only when the job has an embedding", async () => {
    setup({ [`GET /jobs/${A}`]: () => json(detail({ similarAvailable: false })) });
    await screen.findByRole("heading", { name: "Java Engineer" });
    expect(screen.queryByRole("link", { name: "Similar jobs" })).not.toBeInTheDocument();
  });

  it("saves the job", async () => {
    const user = userEvent.setup();
    const api = setup({
      [`GET /jobs/${A}`]: () => json(detail()),
      [`PUT /jobs/${A}/save`]: () => new Response(null, { status: 204 }),
    });
    await user.click(await screen.findByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Saved" })).toHaveAttribute("aria-pressed", "true"));
    expect(api.callsTo("PUT", `/jobs/${A}/save`)).toHaveLength(1);
  });

  it("marks the job as applied and takes it back", async () => {
    const user = userEvent.setup();
    let applied = false;
    const api = setup({
      [`GET /jobs/${A}`]: () => json(detail({ applied })),
      [`PUT /jobs/${A}/applied`]: () => {
        applied = true;
        return new Response(null, { status: 204 });
      },
      [`DELETE /jobs/${A}/applied`]: () => {
        applied = false;
        return new Response(null, { status: 204 });
      },
    });
    await user.click(await screen.findByRole("button", { name: "Mark as applied" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Applied" })).toHaveAttribute("aria-pressed", "true"));
    expect(api.callsTo("PUT", `/jobs/${A}/applied`)).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Applied" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Mark as applied" })).toHaveAttribute("aria-pressed", "false"));
    expect(api.callsTo("DELETE", `/jobs/${A}/applied`)).toHaveLength(1);
  });

  it("says when a job is missing", async () => {
    setup({ [`GET /jobs/${A}`]: () => json({ detail: "nope", code: "job_not_found" }, 404) });
    expect(await screen.findByRole("alert")).toHaveTextContent("could not be found");
    expect(screen.getByRole("link", { name: "Back to jobs" })).toHaveAttribute("href", "/jobs");
  });
});
