import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { AssistedApply } from "./assisted-apply";
import { ANSWERS, CV, JOB, LETTER, PACK, answersDraft, cvDraft, letterDraft, pack } from "./fixtures";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const approvedPack = () =>
  pack([
    { type: "TAILORED_RESUME", state: "READY", document: cvDraft({ status: "APPROVED" }) },
    { type: "COVER_LETTER", state: "READY", document: letterDraft({ status: "APPROVED" }) },
    {
      type: "SCREENING_ANSWERS",
      state: "READY",
      document: answersDraft({
        status: "APPROVED",
        content: { answers: [{ id: "YEARS", question: "Years of experience?", answer: "8 years", status: "FROM_PROFILE" }] },
      }),
    },
  ]);

const created = (existed = false) => json({ id: "app1", jobId: JOB, title: "Java Engineer", status: "APPLIED" }, existed ? 200 : 201);

function setup(p: ReturnType<typeof pack>, applyUrl: string | undefined, routes: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ "POST /applications": () => created(), [`PUT /jobs/${JOB}/applied`]: () => new Response(null, { status: 204 }), ...routes });
  hoisted.client = api.client;
  renderWithQueryClient(<AssistedApply jobId={JOB} pack={p} applyUrl={applyUrl} />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("AssistedApply", () => {
  it("opens the apply URL in a new tab without opener access", () => {
    setup(approvedPack(), "https://acme.example/apply");
    const link = screen.getByRole("link", { name: /Open application page/ });
    expect(link).toHaveAttribute("href", "https://acme.example/apply");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toMatch(/noopener/);
    expect(link.getAttribute("rel")).toMatch(/noreferrer/);
    expect(screen.queryByLabelText(/Paste the link/)).not.toBeInTheDocument();
  });

  it("never links a non-http apply URL and lets the person paste one instead", async () => {
    const user = userEvent.setup();
    setup(approvedPack(), "javascript:alert(1)");
    expect(screen.queryByRole("link", { name: /Open application page/ })).not.toBeInTheDocument();
    const field = screen.getByLabelText(/This job has no apply link/);
    await user.type(field, "javascript:alert(2)");
    expect(screen.getByRole("alert")).toHaveTextContent("Only http or https links can be opened.");
    expect(screen.queryByRole("link", { name: /Open application page/ })).not.toBeInTheDocument();
    await user.clear(field);
    await user.type(field, "https://careers.example/jobs/1");
    expect(screen.getByRole("link", { name: /Open application page/ })).toHaveAttribute("href", "https://careers.example/jobs/1");
  });

  it("copies the letter and each answer", async () => {
    const user = userEvent.setup();
    setup(approvedPack(), "https://acme.example/apply");
    await user.click(screen.getByRole("button", { name: "Copy cover letter" }));
    expect(await navigator.clipboard.readText()).toContain("Dear hiring team,\n\nFirst paragraph.");
    await user.click(screen.getByRole("button", { name: "Copy answer to: Years of experience?" }));
    expect(await navigator.clipboard.readText()).toBe("8 years");
    expect(screen.getByText("answer to: Years of experience? copied")).toBeInTheDocument();
  });

  it("offers to copy only what is approved", () => {
    setup(pack([{ type: "COVER_LETTER", state: "READY", document: letterDraft() }]), "https://acme.example/apply");
    expect(screen.queryByRole("button", { name: "Copy cover letter" })).not.toBeInTheDocument();
    expect(screen.getByText(/Approve the cover letter or the answers to copy them here/)).toBeInTheDocument();
    expect(screen.getByText(/Cover letter: not approved yet/)).toBeInTheDocument();
  });

  it("creates the tracker entry with the pack and the approved document ids, then links to it", async () => {
    const user = userEvent.setup();
    const api = setup(approvedPack(), "https://acme.example/apply");
    await user.click(screen.getByRole("button", { name: "I applied" }));

    expect(await screen.findByRole("link", { name: "Open the tracker entry" })).toHaveAttribute("href", "/applications/app1");
    expect(api.callsTo("POST", "/applications")[0].body).toEqual({
      jobId: JOB,
      packId: PACK,
      resumeDocumentId: CV,
      coverLetterDocumentId: LETTER,
      screeningAnswersDocumentId: ANSWERS,
    });
    expect(screen.getByText("Added to your tracker as Applied.")).toBeInTheDocument();
    await waitFor(() => expect(api.callsTo("PUT", `/jobs/${JOB}/applied`)).toHaveLength(1));
  });

  it("leaves out documents that are not approved and sends a pasted link as the url", async () => {
    const user = userEvent.setup();
    const api = setup(
      pack([
        { type: "TAILORED_RESUME", state: "READY", document: cvDraft({ status: "APPROVED" }) },
        { type: "COVER_LETTER", state: "READY", document: letterDraft() },
      ]),
      undefined,
    );
    await user.type(screen.getByLabelText(/This job has no apply link/), "https://careers.example/jobs/1");
    await user.click(screen.getByRole("button", { name: "I applied" }));
    await screen.findByRole("link", { name: "Open the tracker entry" });
    expect(api.callsTo("POST", "/applications")[0].body).toEqual({
      jobId: JOB,
      packId: PACK,
      resumeDocumentId: CV,
      url: "https://careers.example/jobs/1",
    });
  });

  it("says when the job was already tracked", async () => {
    const user = userEvent.setup();
    setup(approvedPack(), "https://acme.example/apply", { "POST /applications": () => created(true) });
    await user.click(screen.getByRole("button", { name: "I applied" }));
    expect(await screen.findByText("This job was already in your tracker.")).toBeInTheDocument();
  });

  it("shows why it failed and lets the person retry", async () => {
    const user = userEvent.setup();
    let fail = true;
    const api = setup(approvedPack(), "https://acme.example/apply", {
      "POST /applications": () => (fail ? json({ status: 409, code: "application_limit_reached" }, 409) : created()),
    });
    await user.click(screen.getByRole("button", { name: "I applied" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/maximum number of applications/);
    fail = false;
    await user.click(screen.getByRole("button", { name: "I applied" }));
    expect(await screen.findByRole("link", { name: "Open the tracker entry" })).toBeInTheDocument();
    expect(api.callsTo("POST", "/applications")).toHaveLength(2);
  });
});
