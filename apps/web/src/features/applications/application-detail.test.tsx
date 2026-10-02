import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { ApplicationDetailView } from "./application-detail";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const ID = "11111111-1111-4111-8111-111111111111";
const R1 = "99999999-9999-4999-8999-999999999999";

const detail = (extra: Record<string, unknown> = {}) => ({
  id: ID,
  jobId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  title: "Platform Engineer",
  company: "Acme",
  url: "https://acme.example/jobs/1",
  status: "APPLIED",
  notes: "Spoke to Sam.",
  appliedAt: "2026-09-20T10:00:00Z",
  updatedAt: "2026-09-21T10:00:00Z",
  events: [
    { id: "e1", to: "SAVED", at: "2026-09-19T10:00:00Z" },
    { id: "e2", from: "SAVED", to: "APPLIED", note: "Applied on the site", at: "2026-09-20T10:00:00Z" },
  ],
  reminders: [{ id: R1, applicationId: ID, kind: "FOLLOW_UP", dueAt: "2027-01-01T10:00:00Z", state: "PENDING", note: "Ping them" }],
  ...extra,
});

function setup(routes: Parameters<typeof fakeApi>[0] = {}, app = detail()) {
  const api = fakeApi({ [`GET /applications/${ID}`]: () => json(app), ...routes });
  hoisted.client = api.client;
  renderWithQueryClient(<ApplicationDetailView id={ID} />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("ApplicationDetailView", () => {
  it("shows the application with its status history and a safe posting link", async () => {
    setup();
    expect(await screen.findByRole("heading", { name: "Platform Engineer" })).toBeInTheDocument();
    const posting = screen.getByRole("link", { name: /Posting/ });
    expect(posting).toHaveAttribute("href", "https://acme.example/jobs/1");
    expect(posting.getAttribute("rel")).toMatch(/noopener/);
    const history = screen.getByRole("list", { name: "Status history" });
    expect(within(history).getByText("Started as Saved")).toBeInTheDocument();
    expect(within(history).getByText("Saved to Applied")).toBeInTheDocument();
    expect(within(history).getByText("Applied on the site")).toBeInTheDocument();
  });

  it("does not render an unsafe posting link", async () => {
    setup({}, detail({ url: "javascript:alert(1)" }));
    await screen.findByRole("heading", { name: "Platform Engineer" });
    expect(screen.queryByRole("link", { name: /Posting/ })).not.toBeInTheDocument();
  });

  it("saves notes with PATCH", async () => {
    const user = userEvent.setup();
    const api = setup({ [`PATCH /applications/${ID}`]: () => json(detail({ notes: "New notes", updatedAt: "2026-09-22T10:00:00Z" })) });
    const notes = await screen.findByRole("textbox", { name: /Notes/ });
    await user.clear(notes);
    await user.type(notes, "New notes");
    await user.click(screen.getByRole("button", { name: "Save notes" }));
    expect(await screen.findByText("Saved")).toBeInTheDocument();
    expect(api.callsTo("PATCH", `/applications/${ID}`)[0].body).toEqual({ notes: "New notes" });
  });

  it("changes the status with a note and shows a refused move", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /applications/${ID}/status`]: () => json({ status: 409, code: "invalid_transition" }, 409) });
    await user.selectOptions(await screen.findByRole("combobox", { name: "Status" }), "SAVED");
    await user.type(screen.getByLabelText(/Note for the history/), "oops");
    await user.click(screen.getByRole("button", { name: "Update status" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/cannot go back to Saved/);
    expect(api.callsTo("POST", `/applications/${ID}/status`)[0].body).toEqual({ status: "SAVED", note: "oops" });
  });

  it("lists reminders, adds one and cancels a pending one", async () => {
    const user = userEvent.setup();
    const api = setup({
      [`POST /applications/${ID}/reminders`]: () => json({ id: "r2", kind: "INTERVIEW", state: "PENDING" }, 201),
      [`DELETE /applications/${ID}/reminders/${R1}`]: () => new Response(null, { status: 204 }),
    });
    const list = await screen.findByRole("list", { name: "Reminders" });
    expect(within(list).getByText("Ping them")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Kind"), "INTERVIEW");
    await user.type(screen.getByLabelText("When"), "2030-05-01T09:30");
    await user.type(screen.getByLabelText("Note (optional)"), "Panel");
    await user.click(screen.getByRole("button", { name: "Add reminder" }));
    await waitFor(() => expect(api.callsTo("POST", `/applications/${ID}/reminders`)).toHaveLength(1));
    const body = api.callsTo("POST", `/applications/${ID}/reminders`)[0].body as { dueAt: string; kind: string; note: string };
    expect(body).toMatchObject({ kind: "INTERVIEW", note: "Panel" });
    expect(new Date(body.dueAt).getFullYear()).toBe(2030);

    await user.click(screen.getByRole("button", { name: /Cancel reminder on/ }));
    await waitFor(() => expect(api.callsTo("DELETE", `/applications/${ID}/reminders/${R1}`)).toHaveLength(1));
  });

  it("refuses a reminder in the past without asking the server", async () => {
    const user = userEvent.setup();
    const api = setup();
    await user.type(await screen.findByLabelText("When"), "2020-01-01T09:00");
    await user.click(screen.getByRole("button", { name: "Add reminder" }));
    expect(screen.getByRole("alert")).toHaveTextContent("The reminder must be in the future.");
    expect(api.callsTo("POST", `/applications/${ID}/reminders`)).toHaveLength(0);
  });

  it("explains why a closed application takes no reminders, and why one was cancelled", async () => {
    setup(
      {},
      detail({ status: "REJECTED", reminders: [{ id: R1, kind: "FOLLOW_UP", dueAt: "2027-01-01T10:00:00Z", state: "CANCELLED", cancelReason: "APPLICATION_CLOSED" }] }),
    );
    expect(await screen.findByText(/closed, so reminders cannot be added/)).toBeInTheDocument();
    expect(screen.getByText(/Cancelled: application closed/)).toBeInTheDocument();
  });
});

describe("follow-up draft", () => {
  it("shows subject and body with copy buttons and says nothing was sent", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /applications/${ID}/follow-up-draft`]: () => json({ subject: "Following up", body: "Hello,\n\nChecking in.", tone: "WARM", length: "SHORT" }) });
    await user.selectOptions(await screen.findByLabelText("Tone"), "WARM");
    await user.click(screen.getByRole("button", { name: "Write a follow-up draft" }));

    expect(await screen.findByText(/This is a draft\. Nothing has been sent/)).toBeInTheDocument();
    expect(screen.getByText("Following up")).toBeInTheDocument();
    expect(api.callsTo("POST", `/applications/${ID}/follow-up-draft`)[0].body).toEqual({ tone: "WARM", length: "SHORT" });

    await user.click(screen.getByRole("button", { name: "Copy subject" }));
    expect(await navigator.clipboard.readText()).toBe("Following up");
    await user.click(screen.getByRole("button", { name: "Copy email body" }));
    expect(await navigator.clipboard.readText()).toBe("Hello,\n\nChecking in.");
  });

  it.each([
    [409, { code: "not_applied_yet" }, /Mark this application as applied/],
    [429, { code: "ai_daily_cap_reached", resetsAt: "2026-10-03T00:00:00Z" }, /AI allowance\. It comes back on .*Oct/],
    [503, { code: "follow_up_unavailable" }, /unavailable right now/],
    [503, { code: "follow_up_rejected" }, /claimed something your CV does not show/],
  ])("handles %s %j", async (status, body, expected) => {
    const user = userEvent.setup();
    setup({ [`POST /applications/${ID}/follow-up-draft`]: () => json({ status, ...body }, status) });
    await user.click(await screen.findByRole("button", { name: "Write a follow-up draft" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(expected);
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });
});
