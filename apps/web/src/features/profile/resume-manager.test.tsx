import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { ResumeManager } from "./resume-manager";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

const resume = (id: string, label: string, extra: Record<string, unknown> = {}) => ({
  id,
  label,
  fileType: "PDF",
  sizeBytes: 2048,
  primary: false,
  parseStatus: "PARSED",
  createdAt: "2026-09-30T10:00:00Z",
  ...extra,
});

function setup(routes: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi(routes);
  hoisted.client = api.client;
  renderWithQueryClient(<ResumeManager />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
});

describe("ResumeManager", () => {
  it("lists CVs with their primary marker and parse state", async () => {
    setup({
      "GET /resumes": () =>
        json([
          resume(A, "Main CV", { primary: true }),
          resume(B, "Scanned CV", { parseStatus: "FAILED", parseError: "no_extractable_text" }),
          resume(C, "New CV", { parseStatus: "PENDING" }),
        ]),
    });

    const list = await screen.findByRole("list", { name: "Your CVs" });
    const [main, scanned, fresh] = within(list).getAllByRole("listitem");
    expect(within(main).getByText("Primary")).toBeInTheDocument();
    expect(within(main).getByText("Ready")).toBeInTheDocument();
    expect(within(main).queryByRole("button", { name: /Make .* primary/ })).not.toBeInTheDocument();
    expect(within(scanned).getByText("Couldn't read")).toBeInTheDocument();
    expect(within(scanned).getByText(/couldn't find any text/)).toBeInTheDocument();
    expect(within(fresh).getByText("Reading…")).toBeInTheDocument();
  });

  it("makes another CV primary", async () => {
    const user = userEvent.setup();
    let primary = A;
    const api = setup({
      "GET /resumes": () => json([resume(A, "Main CV", { primary: primary === A }), resume(B, "Other CV", { primary: primary === B })]),
      [`PUT /resumes/${B}/primary`]: () => {
        primary = B;
        return json(resume(B, "Other CV", { primary: true }));
      },
    });

    await user.click(await screen.findByRole("button", { name: "Make Other CV primary" }));

    expect(await screen.findByRole("button", { name: "Make Main CV primary" })).toBeInTheDocument();
    expect(api.callsTo("PUT", `/resumes/${B}/primary`)).toHaveLength(1);
  });

  it("asks before deleting and only then deletes", async () => {
    const user = userEvent.setup();
    let resumes = [resume(A, "Main CV", { primary: true }), resume(B, "Other CV")];
    const api = setup({
      "GET /resumes": () => json(resumes),
      [`DELETE /resumes/${B}`]: () => {
        resumes = [resumes[0]];
        return new Response(null, { status: 204 });
      },
    });

    await user.click(await screen.findByRole("button", { name: "Delete Other CV" }));
    expect(api.callsTo("DELETE", `/resumes/${B}`)).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Delete Other CV" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete Other CV" }));
    await user.click(screen.getByRole("button", { name: "Confirm delete Other CV" }));

    await waitFor(() => expect(screen.queryByText("Other CV")).not.toBeInTheDocument());
    expect(api.callsTo("DELETE", `/resumes/${B}`)).toHaveLength(1);
  });

  it("shows the server's reason when an upload is refused", async () => {
    const user = userEvent.setup();
    const api = setup({
      "GET /resumes": () => json([]),
      "POST /resumes": () => json({ status: 409, code: "resume_limit_reached", detail: "You can keep at most 5 CVs." }, 409),
    });
    expect(await screen.findByText("You haven't uploaded a CV yet.")).toBeInTheDocument();

    await user.upload(screen.getByLabelText("CV file"), new File(["%PDF-1.4"], "cv.pdf", { type: "application/pdf" }));
    await user.click(screen.getByRole("button", { name: "Upload CV" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("You can keep at most 5 CVs.");
    expect(api.callsTo("POST", "/resumes")).toHaveLength(1);
  });

  it("tells a user the daily limit blocked a CV, when it resets, and reads it again once allowed", async () => {
    const user = userEvent.setup();
    let blocked = true;
    const api = setup({
      "GET /resumes": () =>
        json([
          blocked
            ? resume(A, "Main CV", { parseStatus: "FAILED", parseError: "ai_daily_cap_reached" })
            : resume(A, "Main CV", { parseStatus: "PENDING" }),
        ]),
      "GET /billing/allowance": () =>
        json({ dailyCap: 500, used: 520, remaining: 0, resetsAt: "2026-10-02T00:00:00Z", exhausted: true }),
      [`POST /resumes/${A}/reparse`]: () => {
        if (blocked) {
          return json(
            { status: 429, code: "ai_daily_cap_reached", detail: "You have reached today's AI usage limit. It resets at 2026-10-02T00:00:00Z." },
            429,
          );
        }
        return json(resume(A, "Main CV", { parseStatus: "PENDING" }), 202);
      },
    });

    const item = within(await screen.findByRole("list", { name: "Your CVs" })).getByRole("listitem");
    expect(within(item).getByText(/reached today's AI limit/)).toBeInTheDocument();
    expect(await within(item).findByText(/You can try again after/)).toBeInTheDocument();

    // Too early: the server's own message (with the reset time) is shown and the CV stays as it was.
    await user.click(within(item).getByRole("button", { name: "Read Main CV again" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("resets at 2026-10-02T00:00:00Z");
    expect(within(item).getByText("Couldn't read")).toBeInTheDocument();

    blocked = false;
    await user.click(within(item).getByRole("button", { name: "Read Main CV again" }));
    await waitFor(() => expect(within(item).getByText("Reading…")).toBeInTheDocument());
    expect(api.callsTo("POST", `/resumes/${A}/reparse`)).toHaveLength(2);
  });

  it("offers no retry for a CV that failed because of the file", async () => {
    setup({
      "GET /resumes": () => json([resume(A, "Scan", { parseStatus: "FAILED", parseError: "no_extractable_text" })]),
    });

    const item = within(await screen.findByRole("list", { name: "Your CVs" })).getByRole("listitem");
    expect(within(item).queryByRole("button", { name: /again/ })).not.toBeInTheDocument();
  });

  it("rejects an unsupported file before uploading it", async () => {
    const user = userEvent.setup({ applyAccept: false });
    const api = setup({ "GET /resumes": () => json([]) });
    await screen.findByText("You haven't uploaded a CV yet.");

    await user.upload(screen.getByLabelText("CV file"), new File(["hi"], "notes.txt", { type: "text/plain" }));
    await user.click(screen.getByRole("button", { name: "Upload CV" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("PDF or DOCX");
    expect(api.callsTo("POST", "/resumes")).toHaveLength(0);
  });
});
