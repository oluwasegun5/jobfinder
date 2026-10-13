import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { TrialReviewQueue } from "./trial-review";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown, search: "" }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/admin/trials",
  useSearchParams: () => new URLSearchParams(hoisted.search),
}));

const DENIED_ID = "11111111-1111-4111-8111-111111111111";
const HELD_ID = "22222222-2222-4222-8222-222222222222";

const denied = {
  userId: DENIED_ID,
  maskedEmail: "a***@mailinator.com",
  signedUpAt: "2026-10-01T09:30:00Z",
  state: "DENIED",
  reviewStatus: "PENDING",
  riskScore: 0,
  reasons: [{ code: "disposable-domain", points: 0, hard: true }],
  milestonesReached: ["M1", "M2"],
  milestonesGranted: [],
};
const held = {
  userId: HELD_ID,
  maskedEmail: "b***@gmail.com",
  signedUpAt: "2026-10-02T09:30:00Z",
  state: "HOLD",
  reviewStatus: "PENDING",
  riskScore: 45,
  reasons: [
    { code: "cv-phone", points: 30, hard: false },
    { code: "fast-onboarding", points: 10, hard: false },
    { code: "google-sign-in", points: -5, hard: false },
  ],
  milestonesReached: ["M1"],
  milestonesGranted: ["M1"],
  trialExpiresAt: "2026-11-01T09:30:00Z",
};

const page = (items: unknown[], over: Record<string, unknown> = {}) => ({
  items,
  page: 0,
  size: 20,
  totalElements: items.length,
  totalPages: 1,
  ...over,
});

const detailOf = (item: unknown, decisions: unknown[] = []) => ({ item, decisions });

function setup(routes: Parameters<typeof fakeApi>[0], search = "") {
  hoisted.search = search;
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<TrialReviewQueue />);
  return api;
}

const queueRoutes = () => ({
  "GET /admin/trials": () => json(page([denied, held])),
  [`GET /admin/trials/${DENIED_ID}`]: () => json(detailOf(denied)),
  [`GET /admin/trials/${HELD_ID}`]: () => json(detailOf(held)),
});

beforeEach(() => {
  hoisted.client = undefined;
  replace.mockClear();
});

describe("TrialReviewQueue list", () => {
  it("lists the accounts in the order sent, with a masked email, state, score and the first reason", async () => {
    const api = setup(queueRoutes());

    const table = await screen.findByRole("table");
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByRole("rowheader")).toHaveTextContent("a***@mailinator.com");
    expect(within(rows[0]).getByText("Denied")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Disposable email domain")).toBeInTheDocument();
    expect(within(rows[1]).getByText("On hold")).toBeInTheDocument();
    expect(within(rows[1]).getByText("45")).toBeInTheDocument();
    expect(within(rows[1]).getByText(/Phone number in the CV matches another account/)).toBeInTheDocument();
    expect(within(rows[1]).getByText(/and 2 more/)).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("2 accounts");
    const url = new URL((api.fetch.mock.calls[0][0] as Request).url);
    expect(url.searchParams.get("view")).toBe("PENDING");
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.get("size")).toBe("20");
    expect(url.searchParams.has("state")).toBe(false);
  });

  it("never shows an email address, hash or anything but the masked address", async () => {
    setup(queueRoutes());

    await screen.findByRole("table");

    expect(document.body.textContent).not.toMatch(/digest|hash|pepper/i);
    expect(document.body.textContent).not.toMatch(/[a-z0-9]{2,}@/i);
  });

  it("asks for the filters held in the URL", async () => {
    const api = setup(queueRoutes(), "view=DECIDED&state=DENIED&page=3");

    await screen.findByRole("table");

    const url = new URL((api.fetch.mock.calls[0][0] as Request).url);
    expect(url.searchParams.get("view")).toBe("DECIDED");
    expect(url.searchParams.get("state")).toBe("DENIED");
    expect(url.searchParams.get("page")).toBe("2");
    expect(screen.getByLabelText("Show")).toHaveValue("DECIDED");
    expect(screen.getByLabelText("State")).toHaveValue("DENIED");
  });

  it("ignores filters it does not know", async () => {
    const api = setup(queueRoutes(), "view=EVERYTHING&state=MAYBE&page=-4");

    await screen.findByRole("table");

    const url = new URL((api.fetch.mock.calls[0][0] as Request).url);
    expect(url.searchParams.get("view")).toBe("PENDING");
    expect(url.searchParams.has("state")).toBe(false);
    expect(url.searchParams.get("page")).toBe("0");
  });

  it("puts a changed state filter in the URL and goes back to the first page", async () => {
    const user = userEvent.setup();
    setup(queueRoutes(), "page=2");
    await screen.findByRole("table");

    await user.selectOptions(screen.getByLabelText("State"), "HOLD");

    expect(replace).toHaveBeenLastCalledWith("/admin/trials?state=HOLD");
  });

  it("switches between waiting and decided accounts through the URL", async () => {
    const user = userEvent.setup();
    setup(queueRoutes(), "state=HOLD");
    await screen.findByRole("table");

    await user.selectOptions(screen.getByLabelText("Show"), "DECIDED");

    expect(replace).toHaveBeenLastCalledWith("/admin/trials?view=DECIDED&state=HOLD");
  });

  it("pages through the queue", async () => {
    const user = userEvent.setup();
    setup({ "GET /admin/trials": () => json(page([denied], { totalElements: 45, totalPages: 3 })) }, "page=2");
    await screen.findByRole("table");

    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(replace).toHaveBeenLastCalledWith("/admin/trials?page=3");
    await user.click(screen.getByRole("button", { name: "Previous" }));
    expect(replace).toHaveBeenLastCalledWith("/admin/trials");
  });

  it("says so when nothing is waiting", async () => {
    setup({ "GET /admin/trials": () => json(page([])) });

    expect(await screen.findByText("Nothing waiting for review")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("shows a not-allowed message to someone who is not an admin", async () => {
    setup({ "GET /admin/trials": () => json({ code: "forbidden", detail: "No" }, 403) });

    expect(await screen.findByText("Not allowed")).toBeInTheDocument();
  });

  it("shows the server's message and lets the admin try again", async () => {
    const user = userEvent.setup();
    const routes: Parameters<typeof fakeApi>[0] = {
      "GET /admin/trials": () => json({ code: "boom", detail: "The queue is unavailable" }, 500),
    };
    setup(routes);

    expect(await screen.findByRole("alert")).toHaveTextContent("The queue is unavailable");
    routes["GET /admin/trials"] = () => json(page([denied]));
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("table")).toBeInTheDocument();
  });
});

describe("TrialReviewQueue drawer", () => {
  it("opens a dialog with the reasons, their points and the decisions so far", async () => {
    const user = userEvent.setup();
    const decision = {
      adminId: "99999999-9999-4999-8999-999999999999",
      action: "APPROVE",
      note: "Checked with support",
      creditsGranted: 30,
      decidedAt: "2026-10-05T10:00:00Z",
    };
    setup({
      ...queueRoutes(),
      [`GET /admin/trials/${HELD_ID}`]: () => json(detailOf({ ...held, reviewStatus: "APPROVED", state: "GRANTED" }, [decision])),
    });
    await screen.findByRole("table");

    await user.click(screen.getByRole("button", { name: "Review b***@gmail.com" }));

    const dialog = await screen.findByRole("dialog", { name: "b***@gmail.com" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(await within(dialog).findByText("Phone number in the CV matches another account")).toBeInTheDocument();
    expect(within(dialog).getByText("+30 points")).toBeInTheDocument();
    expect(within(dialog).getByText("-5 points")).toBeInTheDocument();
    expect(within(dialog).getByText(HELD_ID)).toBeInTheDocument();
    expect(within(dialog).getByText("Checked with support")).toBeInTheDocument();
    expect(within(dialog).getByText(/By admin 99999999/)).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("marks a hard rule as such and says when no reason was recorded", async () => {
    const user = userEvent.setup();
    setup({
      ...queueRoutes(),
      [`GET /admin/trials/${HELD_ID}`]: () => json(detailOf({ ...held, reasons: [] })),
    });
    await screen.findByRole("table");

    await user.click(screen.getByRole("button", { name: "Review a***@mailinator.com" }));
    expect(await screen.findByText("Hard rule")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await user.click(screen.getByRole("button", { name: "Review b***@gmail.com" }));

    expect(await screen.findByText(/No reason was recorded/)).toBeInTheDocument();
  });

  it("closes with Escape and gives focus back to the button that opened it", async () => {
    const user = userEvent.setup();
    setup(queueRoutes());
    await screen.findByRole("table");
    const opener = screen.getByRole("button", { name: "Review a***@mailinator.com" });

    await user.click(opener);
    const dialog = await screen.findByRole("dialog");
    await waitFor(() => expect(dialog).toHaveFocus());
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it("keeps Tab inside the dialog", async () => {
    const user = userEvent.setup();
    setup(queueRoutes());
    await screen.findByRole("table");
    await user.click(screen.getByRole("button", { name: "Review a***@mailinator.com" }));
    const dialog = await screen.findByRole("dialog");
    await screen.findByRole("button", { name: "Deny" });

    for (let i = 0; i < 6; i += 1) {
      await user.tab();
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
    }
  });

  it("approves only after a confirmation with a note, then shows what was released", async () => {
    const user = userEvent.setup();
    const routes: Parameters<typeof fakeApi>[0] = {
      ...queueRoutes(),
      [`POST /admin/trials/${DENIED_ID}/approve`]: () =>
        json({ applied: true, creditsGranted: 100, item: { ...denied, state: "GRANTED", reviewStatus: "APPROVED" } }),
    };
    const api = setup(routes);
    await screen.findByRole("table");
    await user.click(screen.getByRole("button", { name: "Review a***@mailinator.com" }));

    await user.click(await screen.findByRole("button", { name: "Approve" }));
    const confirm = screen.getByRole("button", { name: "Confirm approve" });
    expect(screen.getByRole("group", { name: "Approve this trial?" })).toHaveTextContent("M1, M2");
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText("Note (required)"), "   ");
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText("Note (required)"), "Genuine student, checked the CV");
    expect(confirm).toBeEnabled();
    expect(api.callsTo("POST", `/admin/trials/${DENIED_ID}/approve`)).toHaveLength(0);
    await user.click(confirm);

    expect(await screen.findByText("Approved. 100 trial credits released.")).toBeInTheDocument();
    expect(api.callsTo("POST", `/admin/trials/${DENIED_ID}/approve`)[0].body).toEqual({
      note: "Genuine student, checked the CV",
    });
    await waitFor(() => expect(api.callsTo("GET", "/admin/trials").length).toBeGreaterThan(1));
    expect(screen.queryByRole("group")).not.toBeInTheDocument();
  });

  it("denies after a confirmation that says it is final", async () => {
    const user = userEvent.setup();
    const api = setup({
      ...queueRoutes(),
      [`POST /admin/trials/${HELD_ID}/deny`]: () =>
        json({ applied: true, creditsGranted: 0, item: { ...held, state: "DENIED", reviewStatus: "DENIED" } }),
    });
    await screen.findByRole("table");
    await user.click(screen.getByRole("button", { name: "Review b***@gmail.com" }));

    await user.click(await screen.findByRole("button", { name: "Deny" }));
    expect(screen.getByRole("group", { name: "Deny this trial for good?" })).toHaveTextContent("cannot approve");
    await user.type(screen.getByLabelText("Note (required)"), "Same person as an earlier account");
    await user.click(screen.getByRole("button", { name: "Confirm deny" }));

    expect(await screen.findByText("Denied. This is final.")).toBeInTheDocument();
    expect(api.callsTo("POST", `/admin/trials/${HELD_ID}/deny`)[0].body).toEqual({
      note: "Same person as an earlier account",
    });
  });

  it("can step back from a confirmation without sending anything", async () => {
    const user = userEvent.setup();
    const api = setup(queueRoutes());
    await screen.findByRole("table");
    await user.click(screen.getByRole("button", { name: "Review a***@mailinator.com" }));
    await user.click(await screen.findByRole("button", { name: "Approve" }));
    await user.type(screen.getByLabelText("Note (required)"), "Maybe");

    await user.click(screen.getByRole("button", { name: "Back" }));

    expect(screen.queryByRole("group")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(api.calls.filter((c) => c.method === "POST")).toHaveLength(0);
  });

  it("shows the server's reason when a decision is refused and keeps the note", async () => {
    const user = userEvent.setup();
    setup({
      ...queueRoutes(),
      [`POST /admin/trials/${DENIED_ID}/approve`]: () =>
        json({ code: "trial_denied_final", detail: "An admin denied this account earlier; a denial is final" }, 409),
    });
    await screen.findByRole("table");
    await user.click(screen.getByRole("button", { name: "Review a***@mailinator.com" }));
    await user.click(await screen.findByRole("button", { name: "Approve" }));
    await user.type(screen.getByLabelText("Note (required)"), "Looks fine");
    await user.click(screen.getByRole("button", { name: "Confirm approve" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("a denial is final");
    expect(screen.getByLabelText("Note (required)")).toHaveValue("Looks fine");
  });

  it("limits the note to 1000 characters", async () => {
    const user = userEvent.setup();
    setup(queueRoutes());
    await screen.findByRole("table");
    await user.click(screen.getByRole("button", { name: "Review a***@mailinator.com" }));
    await user.click(await screen.findByRole("button", { name: "Deny" }));

    expect(screen.getByLabelText("Note (required)")).toHaveAttribute("maxlength", "1000");
  });
});
