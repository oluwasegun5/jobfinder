import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { ANSWERS, JOB, LETTER, PACK, answersDraft, cvDraft, fullPack, letterDraft, pack } from "./fixtures";
import { TailorWorkspace } from "./tailor-workspace";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const job = { id: JOB, title: "Java Engineer", company: { id: "c1", name: "Acme" }, applyUrl: "https://acme.example/apply", status: "ACTIVE" };

function setup(routes: Parameters<typeof fakeApi>[0], existing: ReturnType<typeof pack> | undefined = fullPack()) {
  const api = fakeApi({
    [`GET /jobs/${JOB}`]: () => json(job),
    "GET /application-packs": () => json({ items: existing ? [{ id: PACK }] : [] }),
    [`GET /application-packs/${PACK}`]: () => json(existing),
    ...routes,
  });
  hoisted.client = api.client;
  renderWithQueryClient(<TailorWorkspace jobId={JOB} />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("TailorWorkspace: making the pack", () => {
  it("offers the options when there is no pack and makes all three parts by default", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /jobs/${JOB}/application-pack`]: () => json(fullPack(), 201) }, undefined);

    await screen.findByRole("heading", { name: "Tailor for Java Engineer" });
    await user.click(screen.getByRole("button", { name: "Make my application pack" }));

    expect(await screen.findByRole("heading", { name: "Progress" })).toBeInTheDocument();
    expect(api.callsTo("POST", `/jobs/${JOB}/application-pack`)[0].body).toEqual({
      include: ["TAILORED_RESUME", "COVER_LETTER", "SCREENING_ANSWERS"],
      tone: "FORMAL",
      length: "STANDARD",
    });
    expect(screen.getByRole("button", { name: "Approve CV" })).toBeInTheDocument();
  });

  it("tailors only the CV when only the CV is ticked", async () => {
    const user = userEvent.setup();
    const api = setup(
      { [`POST /jobs/${JOB}/application-pack`]: () => json(pack([{ type: "TAILORED_RESUME", state: "READY", document: cvDraft() }]), 201) },
      undefined,
    );
    await user.click(await screen.findByRole("checkbox", { name: "Cover letter" }));
    await user.click(screen.getByRole("checkbox", { name: "Screening answers" }));
    await user.click(screen.getByRole("button", { name: "Tailor my CV" }));
    await screen.findByRole("heading", { name: "Progress" });
    expect(api.callsTo("POST", `/jobs/${JOB}/application-pack`)[0].body).toEqual({ include: ["TAILORED_RESUME"], tone: "FORMAL", length: "STANDARD" });
    expect(screen.queryByRole("heading", { name: "Cover letter" })).not.toBeInTheDocument();
  });

  it("cannot make a pack of nothing", async () => {
    const user = userEvent.setup();
    setup({}, undefined);
    for (const name of ["Tailored CV", "Cover letter", "Screening answers"]) await user.click(await screen.findByRole("checkbox", { name }));
    expect(screen.getByRole("button", { name: "Make my application pack" })).toBeDisabled();
  });

  it("shows the daily cap with its reset time when the pack cannot be made", async () => {
    const user = userEvent.setup();
    setup(
      {
        [`POST /jobs/${JOB}/application-pack`]: () =>
          json({ status: 429, code: "ai_daily_cap_reached", detail: "x", resetsAt: "2026-10-03T00:00:00Z" }, 429),
      },
      undefined,
    );
    await user.click(await screen.findByRole("button", { name: "Make my application pack" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/AI allowance\. It comes back on .*Oct/);
  });

  it("sends people without a CV to their profile", async () => {
    const user = userEvent.setup();
    setup({ [`POST /jobs/${JOB}/application-pack`]: () => json({ status: 409, code: "resume_required", detail: "x" }, 409) }, undefined);
    await user.click(await screen.findByRole("button", { name: "Make my application pack" }));
    expect(await screen.findByRole("link", { name: "Go to your CVs" })).toHaveAttribute("href", "/profile/resumes");
  });
});

describe("TailorWorkspace: progress per part", () => {
  const mixed = () =>
    pack(
      [
        { type: "TAILORED_RESUME", state: "READY", document: cvDraft() },
        { type: "COVER_LETTER", state: "BLOCKED_BY_CAP", error: { code: "ai_daily_cap_reached", message: "cap", retryable: true, resetsAt: "2026-10-03T00:00:00Z" } },
        { type: "SCREENING_ANSWERS", state: "FAILED", error: { code: "writing_unavailable", message: "Writing is unavailable right now.", retryable: true } },
      ],
      { status: "PARTIAL" },
    );

  it("shows ready, capped and failed parts separately, with the reset time for the capped one", async () => {
    setup({}, mixed());
    const list = await screen.findByRole("list", { name: "Pack progress" });
    expect(within(list).getByText(/Tailored CV: Ready for your review/)).toBeInTheDocument();
    expect(within(list).getByText(/Cover letter: Waiting for your daily AI allowance/)).toBeInTheDocument();
    expect(within(list).getByText(/comes back on .*Oct/)).toBeInTheDocument();
    expect(within(list).getByText(/Screening answers: Failed/)).toBeInTheDocument();
    expect(within(list).getByText("Writing is unavailable right now.")).toBeInTheDocument();
    // The part that worked is still there to review.
    expect(screen.getByRole("button", { name: "Approve CV" })).toBeInTheDocument();
  });

  it("retries one part, and all that did not finish", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /application-packs/${PACK}/retry`]: () => json(fullPack()) }, mixed());
    await user.click(await screen.findByRole("button", { name: "Retry Screening answers" }));
    await waitFor(() => expect(api.callsTo("POST", `/application-packs/${PACK}/retry`)).toHaveLength(1));
    expect(api.callsTo("POST", `/application-packs/${PACK}/retry`)[0].body).toEqual({ parts: ["SCREENING_ANSWERS"] });
    // The answer came back: the retry button is gone.
    await waitFor(() => expect(screen.queryByRole("button", { name: /^Retry/ })).not.toBeInTheDocument());
  });

  it("marks a deleted draft as missing and offers a retry", async () => {
    setup({}, pack([{ type: "COVER_LETTER", state: "MISSING", error: { code: "document_missing", message: "This draft was deleted. Retry makes a new one.", retryable: true } }], { status: "PARTIAL" }));
    expect(await screen.findByText(/Cover letter: Draft was deleted/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry Cover letter" })).toBeInTheDocument();
  });

  it("shows the typed error when a retry is still capped", async () => {
    const user = userEvent.setup();
    setup(
      { [`POST /application-packs/${PACK}/retry`]: () => json({ status: 429, code: "ai_daily_cap_reached", resetsAt: "2026-10-03T00:00:00Z" }, 429) },
      mixed(),
    );
    await user.click(await screen.findByRole("button", { name: "Retry Cover letter" }));
    expect(await screen.findAllByText(/comes back on .*Oct/)).not.toHaveLength(0);
  });
});

describe("TailorWorkspace: letter and answers", () => {
  it("saves an edited paragraph as an EDIT with its path and the draft's version", async () => {
    const user = userEvent.setup();
    const edited = letterDraft({ version: 2, content: { salutation: "Dear hiring team,", paragraphs: ["Changed text.", "Second paragraph."], closing: "Kind regards,", signature: "Jordan Reyes" } });
    const api = setup({ [`PATCH /documents/${LETTER}`]: () => json(edited) });

    const box = await screen.findByLabelText("Paragraph 1");
    await user.clear(box);
    await user.type(box, "Changed text.");
    await user.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.getByLabelText("Paragraph 1")).toHaveValue("Changed text."));
    expect(api.callsTo("PATCH", `/documents/${LETTER}`)[0].body).toEqual({
      version: 1,
      operations: [{ op: "EDIT", path: "paragraphs[0]", after: "Changed text." }],
    });
  });

  it("regenerates the letter with another tone and refreshes from the pack", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /jobs/${JOB}/cover-letter`]: () => json(letterDraft({ version: 1 }), 201) });
    await screen.findByLabelText("Paragraph 1");
    const regenerate = screen.getByRole("region", { name: "Write the letter again" });
    await user.selectOptions(within(regenerate).getByLabelText("Tone"), "WARM");
    await user.click(within(regenerate).getByRole("button", { name: "Write letter again" }));
    await waitFor(() => expect(api.callsTo("POST", `/jobs/${JOB}/cover-letter`)).toHaveLength(1));
    expect(api.callsTo("POST", `/jobs/${JOB}/cover-letter`)[0].body).toEqual({ tone: "WARM", length: "STANDARD" });
  });

  it("does not let answers be approved until every NEEDS_INPUT question is answered", async () => {
    const user = userEvent.setup();
    const answered = answersDraft({ version: 2 });
    (answered.content as { answers: { id: string; answer: string; status: string }[] }).answers[0] = {
      id: "NOTICE_PERIOD",
      question: "What is your notice period?",
      answer: "Four weeks",
      status: "USER_PROVIDED",
    } as never;
    const api = setup({ [`PATCH /documents/${ANSWERS}`]: () => json(answered) });

    expect(await screen.findByText(/1 question needs your answer before this can be approved/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve answers" })).toBeDisabled();
    expect(screen.getByText("Say how many weeks.")).toBeInTheDocument();

    await user.type(screen.getByLabelText("What is your notice period?"), "Four weeks");
    await user.click(screen.getByRole("button", { name: "Save 1 answer" }));

    await waitFor(() => expect(screen.queryByText(/needs your answer before this can be approved/)).not.toBeInTheDocument());
    expect(api.callsTo("PATCH", `/documents/${ANSWERS}`)[0].body).toEqual({
      version: 1,
      operations: [{ op: "EDIT", path: "answers.NOTICE_PERIOD", after: "Four weeks" }],
    });
    await user.click(within(document.getElementById("answers")!).getByRole("checkbox", { name: /vouch/ }));
    expect(screen.getByRole("button", { name: "Approve answers" })).toBeEnabled();
  });
});

describe("TailorWorkspace: export", () => {
  it("exports an approved CV and offers a safe download link", async () => {
    const user = userEvent.setup();
    const approved = pack([{ type: "TAILORED_RESUME", state: "READY", document: cvDraft({ status: "APPROVED" }) }]);
    const api = setup(
      {
        [`POST /documents/${approved.parts![0].document!.id}/render`]: () =>
          json({ id: "f1", template: "STYLED", format: "DOCX", pageSize: "LETTER", filename: "cv.docx", sizeBytes: 2048, downloadUrl: "https://files.example/cv.docx?sig=1", expiresAt: "2026-10-03T00:00:00Z" }),
      },
      approved,
    );
    await user.selectOptions(await screen.findByLabelText("Layout"), "STYLED");
    await user.selectOptions(screen.getByLabelText("Format"), "DOCX");
    await user.selectOptions(screen.getByLabelText("Page size"), "LETTER");
    await user.click(screen.getByRole("button", { name: "Export" }));

    const link = await screen.findByRole("link", { name: "Download cv.docx" });
    expect(link).toHaveAttribute("href", "https://files.example/cv.docx?sig=1");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
    expect(api.calls.find((c) => c.path.endsWith("/render"))?.body).toEqual({ template: "STYLED", format: "DOCX", pageSize: "LETTER" });
  });

  it("does not offer export before approval", async () => {
    setup({});
    await screen.findByRole("button", { name: "Approve CV" });
    expect(screen.queryByRole("button", { name: "Export" })).not.toBeInTheDocument();
  });
});
