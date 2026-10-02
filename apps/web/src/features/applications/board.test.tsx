import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { Board } from "./board";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const A = "11111111-1111-4111-8111-111111111111";
const B = "22222222-2222-4222-8222-222222222222";

const view = (id: string, title: string, status: string, extra: Record<string, unknown> = {}) => ({ id, title, company: "Acme", status, ...extra });

const board = () => ({
  board: {
    SAVED: [view(A, "Backend Engineer", "SAVED")],
    APPLIED: [view(B, "Platform Engineer", "APPLIED", { appliedAt: "2026-09-20T10:00:00Z" })],
    SCREENING: [],
    INTERVIEW: [],
    OFFER: [],
    REJECTED: [],
    WITHDRAWN: [],
  },
  counts: { SAVED: 1, APPLIED: 1, SCREENING: 0, INTERVIEW: 0, OFFER: 0, REJECTED: 0, WITHDRAWN: 0 },
  truncated: false,
});

/** Deferred answer, to look at the board while a move is still in flight. */
function deferred() {
  let resolve!: (r: Response) => void;
  const promise = new Promise<Response>((r) => (resolve = r));
  return { promise, resolve };
}

function setup(routes: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ "GET /applications": () => json(board()), ...routes });
  hoisted.client = api.client;
  renderWithQueryClient(<Board />);
  return api;
}

const column = (status: string) => screen.getByTestId(`column-${status}`);
const dataTransfer = (id: string) => ({ getData: () => id, setData: vi.fn(), effectAllowed: "move" });

function drag(card: string, toColumn: HTMLElement) {
  const el = screen.getByTestId(`card-${card}`);
  const dt = dataTransfer(card);
  fireEvent.dragStart(el, { dataTransfer: dt });
  fireEvent.dragOver(toColumn, { dataTransfer: dt });
  fireEvent.drop(toColumn, { dataTransfer: dt });
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("Board", () => {
  it("shows every status as a column with its count, asking for the grouped list", async () => {
    const api = setup();
    await screen.findByRole("heading", { name: /Saved/ });
    for (const label of ["Saved", "Applied", "Screening", "Interview", "Offer", "Rejected", "Withdrawn"]) {
      expect(screen.getByRole("heading", { name: new RegExp(`^${label}`) })).toBeInTheDocument();
    }
    expect(within(column("APPLIED")).getByText("1", { selector: "[aria-label]" })).toBeInTheDocument();
    expect(within(column("SCREENING")).getByText("No applications here.")).toBeInTheDocument();
    expect(within(column("APPLIED")).getByRole("link", { name: "Platform Engineer" })).toHaveAttribute("href", `/applications/${B}`);
    expect(api.calls[0].path).toBe("/applications");
  });

  it("moves a dropped card at once and keeps it there when core-api accepts", async () => {
    const gate = deferred();
    const api = setup({ [`POST /applications/${B}/status`]: () => gate.promise });
    await screen.findByTestId(`card-${B}`);

    drag(B, column("INTERVIEW"));

    // Optimistic: the card is already in Interview, before the server answered.
    await waitFor(() => expect(within(column("INTERVIEW")).getByRole("link", { name: "Platform Engineer" })).toBeInTheDocument());
    expect(within(column("APPLIED")).queryByRole("link", { name: "Platform Engineer" })).not.toBeInTheDocument();
    expect(within(column("INTERVIEW")).getByLabelText("1 applications")).toBeInTheDocument();

    gate.resolve(json(view(B, "Platform Engineer", "INTERVIEW")));
    expect(await screen.findByText("Moved Platform Engineer to Interview.")).toBeInTheDocument();
    expect(api.callsTo("POST", `/applications/${B}/status`)[0].body).toEqual({ status: "INTERVIEW" });
  });

  it("puts the card back and says why when the move is refused (409 invalid_transition)", async () => {
    const gate = deferred();
    const api = setup({ [`POST /applications/${B}/status`]: () => gate.promise });
    await screen.findByTestId(`card-${B}`);

    drag(B, column("SAVED"));
    await waitFor(() => expect(within(column("SAVED")).getByRole("link", { name: "Platform Engineer" })).toBeInTheDocument());

    // After the refusal the board is refetched from the server, which still has the card in Applied.
    gate.resolve(json({ status: 409, code: "invalid_transition", detail: "no" }, 409));

    expect(await screen.findByRole("alert")).toHaveTextContent(/cannot go back to Saved/);
    expect(screen.getByRole("alert")).toHaveTextContent("Platform Engineer is back in Applied.");
    await waitFor(() => expect(within(column("APPLIED")).getByRole("link", { name: "Platform Engineer" })).toBeInTheDocument());
    expect(within(column("SAVED")).queryByRole("link", { name: "Platform Engineer" })).not.toBeInTheDocument();
    expect(within(column("APPLIED")).getByLabelText("1 applications")).toBeInTheDocument();
    expect(api.callsTo("POST", `/applications/${B}/status`)).toHaveLength(1);
  });

  it("restores the exact previous board on a network failure, before any refetch", async () => {
    // The refetch after the failure also fails, so what is on screen is the rollback alone.
    let reads = 0;
    const api = setup({
      "GET /applications": () => (reads++ === 0 ? json(board()) : new Response("down", { status: 500 })),
      [`POST /applications/${A}/status`]: () => json({ status: 500 }, 500),
    });
    await screen.findByTestId(`card-${A}`);
    drag(A, column("OFFER"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Backend Engineer is back in Saved.");
    expect(within(column("SAVED")).getByRole("link", { name: "Backend Engineer" })).toBeInTheDocument();
    expect(within(column("OFFER")).queryByRole("link")).not.toBeInTheDocument();
    expect(api.callsTo("POST", `/applications/${A}/status`)).toHaveLength(1);
  });

  it("ignores a drop on the column the card is already in", async () => {
    const api = setup();
    await screen.findByTestId(`card-${B}`);
    drag(B, column("APPLIED"));
    expect(api.callsTo("POST", `/applications/${B}/status`)).toHaveLength(0);
  });

  it("moves a card with the keyboard through its status menu", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /applications/${B}/status`]: () => json(view(B, "Platform Engineer", "OFFER")) });
    const menu = await screen.findByLabelText("Move Platform Engineer to");
    await user.tab(); // a native select: reachable with Tab, changed with the keyboard or a picker
    while (document.activeElement !== menu && document.activeElement !== document.body) await user.tab();
    expect(menu).toHaveFocus();
    await user.selectOptions(menu, "OFFER");
    await waitFor(() => expect(api.callsTo("POST", `/applications/${B}/status`)).toHaveLength(1));
    expect(api.callsTo("POST", `/applications/${B}/status`)[0].body).toEqual({ status: "OFFER" });
    expect(await screen.findByText("Moved Platform Engineer to Offer.")).toBeInTheDocument();
  });

  it("adds an application by hand and needs a title", async () => {
    const user = userEvent.setup();
    const api = setup({ "POST /applications": () => json(view("33333333-3333-4333-8333-333333333333", "Data Engineer", "APPLIED"), 201) });
    await user.click(await screen.findByRole("button", { name: "Add an application" }));
    await user.click(screen.getByRole("button", { name: "Add application" }));
    expect(screen.getByRole("alert")).toHaveTextContent("A title is required.");
    expect(api.callsTo("POST", "/applications")).toHaveLength(0);

    await user.type(screen.getByLabelText("Job title"), "Data Engineer");
    await user.type(screen.getByLabelText("Company"), "Globex");
    await user.type(screen.getByLabelText("Link to the posting"), "https://globex.example/jobs/7");
    await user.click(screen.getByRole("button", { name: "Add application" }));

    expect(await screen.findByText("Added Data Engineer.")).toBeInTheDocument();
    expect(api.callsTo("POST", "/applications")[0].body).toEqual({
      title: "Data Engineer",
      company: "Globex",
      url: "https://globex.example/jobs/7",
      status: "APPLIED",
    });
  });

  it("rejects a link that is not http or https before sending it", async () => {
    const user = userEvent.setup();
    const api = setup();
    await user.click(await screen.findByRole("button", { name: "Add an application" }));
    await user.type(screen.getByLabelText("Job title"), "Data Engineer");
    await user.type(screen.getByLabelText("Link to the posting"), "javascript:alert(1)");
    await user.click(screen.getByRole("button", { name: "Add application" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Use an http or https link.");
    expect(api.callsTo("POST", "/applications")).toHaveLength(0);
  });

  it("explains a load failure and retries", async () => {
    const user = userEvent.setup();
    let fail = true;
    setup({ "GET /applications": () => (fail ? new Response("x", { status: 500 }) : json(board())) });
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId(`card-${A}`)).toBeInTheDocument();
  });
});
