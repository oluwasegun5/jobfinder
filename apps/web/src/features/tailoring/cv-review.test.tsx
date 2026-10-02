import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { CV, JOB, PACK, blockedCv, cvDraft, pack } from "./fixtures";
import { CvReview } from "./cv-review";
import { partOf, type Draft } from "./model";
import { usePackForJob } from "./queries";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

/** The review screen as the workspace mounts it: its draft comes from the pack query that edits write back into. */
function Harness() {
  const query = usePackForJob(JOB);
  const draft = partOf(query.data, "TAILORED_RESUME")?.document;
  return draft ? <CvReview jobId={JOB} draft={draft} /> : <p>loading</p>;
}

function setup(initial: Draft, extra: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    "GET /application-packs": () => json({ items: [{ id: PACK, status: "COMPLETE" }] }),
    [`GET /application-packs/${PACK}`]: () => json(pack([{ type: "TAILORED_RESUME", state: "READY", document: initial }])),
    ...extra,
  });
  hoisted.client = api.client;
  renderWithQueryClient(<Harness />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("CvReview", () => {
  it("shows original and proposed side by side for every change", async () => {
    setup(cvDraft());
    expect(await screen.findByRole("heading", { name: /Summary/ })).toBeInTheDocument();
    expect(screen.getByText("Backend engineer.")).toBeInTheDocument();
    expect(screen.getByText("Backend engineer focused on payments.")).toBeInTheDocument();
    expect(screen.getByText("2 of 2 changes accepted, 0 rejected.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Accept change: Summary" })).toHaveAttribute("aria-pressed", "true");
  });

  it("rejects one change with the draft's version and shows the stored result", async () => {
    const user = userEvent.setup();
    const rejected = cvDraft({ version: 4 });
    rejected.changes![0].state = "REJECTED";
    const api = setup(cvDraft(), { [`PATCH /documents/${CV}`]: () => json(rejected) });

    await user.click(await screen.findByRole("button", { name: "Reject change: Summary" }));

    await waitFor(() => expect(screen.getByText("1 of 2 changes accepted, 1 rejected.")).toBeInTheDocument());
    expect(api.callsTo("PATCH", `/documents/${CV}`)[0].body).toEqual({
      version: 3,
      operations: [{ op: "SET_STATE", changeId: "c1", state: "REJECTED" }],
    });
    expect(screen.getByRole("button", { name: "Reject change: Summary" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Accept change: Experience 1" })).toHaveAttribute("aria-pressed", "true");
  });

  it("accepts again after a rejection, and sends nothing when the state is already that", async () => {
    const user = userEvent.setup();
    const initial = cvDraft();
    initial.changes![0].state = "REJECTED";
    const api = setup(initial, { [`PATCH /documents/${CV}`]: () => json(cvDraft({ version: 4 })) });

    await user.click(await screen.findByRole("button", { name: "Reject change: Summary" }));
    expect(api.callsTo("PATCH", `/documents/${CV}`)).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "Accept change: Summary" }));
    await waitFor(() => expect(api.callsTo("PATCH", `/documents/${CV}`)).toHaveLength(1));
    expect(api.callsTo("PATCH", `/documents/${CV}`)[0].body).toMatchObject({ operations: [{ changeId: "c1", state: "ACCEPTED" }] });
  });

  it("disables approval while a blocking flag remains, says why, and shows the flag on its change", async () => {
    setup(blockedCv());
    const approve = await screen.findByRole("button", { name: "Approve CV" });
    expect(approve).toBeDisabled();
    expect(screen.getByText(/1 blocking fact-check flag: reject or edit the changes/)).toBeInTheDocument();
    expect(screen.getByText(/Fact check failed: 1 blocking flag\./)).toBeInTheDocument();
    expect(screen.getByText(/Blocking: An employer that is not in your CV\./)).toBeInTheDocument();
    expect(screen.getByText("Globex")).toBeInTheDocument();
    // The posting's injected instruction is a visible warning about the posting, not a hidden detail.
    expect(screen.getByText(/tried to give instructions to an AI/)).toBeInTheDocument();
    expect(screen.getByRole("checkbox")).toBeDisabled();
  });

  it("approves only after the person vouches for the content", async () => {
    const user = userEvent.setup();
    const approved = cvDraft({ status: "APPROVED", version: 4 });
    const api = setup(cvDraft(), { [`POST /documents/${CV}/approve`]: () => json(approved) });

    const approve = await screen.findByRole("button", { name: "Approve CV" });
    expect(approve).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /I vouch that everything in it is true/ }));
    expect(approve).toBeEnabled();
    await user.click(approve);

    expect(await screen.findByText(/Approved\. This CV is final/)).toBeInTheDocument();
    expect(api.callsTo("POST", `/documents/${CV}/approve`)).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Accept change: Summary" })).toBeDisabled();
  });

  it("explains a version conflict and reloads what is stored", async () => {
    const user = userEvent.setup();
    const api = setup(cvDraft(), {
      [`PATCH /documents/${CV}`]: () => json({ status: 409, code: "version_conflict", detail: "changed", currentVersion: 5 }, 409),
    });
    await user.click(await screen.findByRole("button", { name: "Reject change: Summary" }));
    expect(await screen.findByText(/changed in another tab/)).toBeInTheDocument();
    await waitFor(() => expect(api.callsTo("GET", `/application-packs/${PACK}`).length).toBeGreaterThan(1));
  });

  it("shows a blocking approval refusal from the server", async () => {
    const user = userEvent.setup();
    setup(cvDraft(), {
      [`POST /documents/${CV}/approve`]: () => json({ status: 409, code: "fact_check_failed", detail: "blocked" }, 409),
    });
    await user.click(await screen.findByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "Approve CV" }));
    expect(await screen.findByText(/still blocking fact-check flags/)).toBeInTheDocument();
  });
});
