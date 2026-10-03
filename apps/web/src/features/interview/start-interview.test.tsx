import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { JOB, PREP, SESSION, session } from "./fixtures";
import { StartInterview } from "./start-interview";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const job = { id: JOB, title: "Backend Engineer", company: { id: "c1", name: "Harbor Freight Tech" }, status: "ACTIVE" };
const prep = (jobId = JOB) => ({ id: PREP, jobId, status: "READY", questions: [{ category: "behavioral", question: "Q?" }, { category: "technical", question: "T?" }] });

function setup(routes: Parameters<typeof fakeApi>[0] = {}, props: { prepId?: string } = {}) {
  const api = fakeApi({ [`GET /jobs/${JOB}`]: () => json(job), [`POST /interview-sessions`]: () => json(session(), 201), ...routes });
  hoisted.client = api.client;
  renderWithQueryClient(<StartInterview jobId={JOB} {...props} />);
  return api;
}

beforeEach(() => {
  hoisted.client = undefined;
  push.mockReset();
});

describe("StartInterview", () => {
  it("starts an interview for the job and opens it", async () => {
    const user = userEvent.setup();
    const api = setup();

    expect(await screen.findByRole("heading", { level: 1, name: /Practise the interview for Backend Engineer/ })).toBeInTheDocument();
    expect(screen.getByText(/at Harbor Freight Tech/)).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Number of questions"), "4");
    await user.click(screen.getByRole("button", { name: "Start interview" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/interviews/${SESSION}`));
    expect(api.callsTo("POST", "/interview-sessions")[0].body).toEqual({ jobId: JOB, maxTurns: 4 });
  });

  it("shows that it is working while the first question is made", async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => (release = resolve));
    setup({
      [`POST /interview-sessions`]: async () => {
        await held;
        return json(session(), 201);
      },
    });

    await user.click(await screen.findByRole("button", { name: "Start interview" }));

    expect(await screen.findByRole("button", { name: /Starting/ })).toBeDisabled();
    expect(screen.getByText("Getting your first question…")).toBeInTheDocument();
    release();
    await waitFor(() => expect(push).toHaveBeenCalled());
  });

  it("starts from a prep made for this job and says so", async () => {
    const user = userEvent.setup();
    const api = setup({ [`GET /interview-prep/${PREP}`]: () => json(prep()) }, { prepId: PREP });

    expect(await screen.findByText(/first question comes from your interview prep \(2 questions\)/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Start interview" }));

    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(api.callsTo("POST", "/interview-sessions")[0].body).toEqual({ jobId: JOB, maxTurns: 5, prepId: PREP });
  });

  it("does not use a prep made for another job", async () => {
    const user = userEvent.setup();
    const api = setup({ [`GET /interview-prep/${PREP}`]: () => json(prep("dddddddd-dddd-4ddd-8ddd-dddddddddddd")) }, { prepId: PREP });

    expect(await screen.findByText(/made for a different job, so it will not be used/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Start interview" }));

    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(api.callsTo("POST", "/interview-sessions")[0].body).toEqual({ jobId: JOB, maxTurns: 5 });
  });

  it("starts without the prep when it cannot be loaded", async () => {
    const user = userEvent.setup();
    const api = setup({ [`GET /interview-prep/${PREP}`]: () => json({ status: 404, code: "interview_prep_not_found" }, 404) }, { prepId: PREP });

    expect(await screen.findByText(/prep could not be loaded/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Start interview" }));

    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(api.callsTo("POST", "/interview-sessions")[0].body).toEqual({ jobId: JOB, maxTurns: 5 });
  });

  it("says the daily allowance is used up and does not navigate", async () => {
    const user = userEvent.setup();
    setup({ [`POST /interview-sessions`]: () => json({ status: 429, code: "ai_daily_cap_reached" }, 429) });

    await user.click(await screen.findByRole("button", { name: "Start interview" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/used today's AI allowance/);
    expect(push).not.toHaveBeenCalled();
  });

  it("says the interviewer is unavailable on a 503 and lets the person try again", async () => {
    const user = userEvent.setup();
    let calls = 0;
    setup({
      [`POST /interview-sessions`]: () => {
        calls += 1;
        return calls === 1 ? json({ status: 503, code: "mock_interview_unavailable" }, 503) : json(session(), 201);
      },
    });

    await user.click(await screen.findByRole("button", { name: "Start interview" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/interviewer is unavailable right now/);
    await user.click(screen.getByRole("button", { name: "Try again" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/interviews/${SESSION}`));
  });

  it("resumes the interview already open for the job when core-api answers 200", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /interview-sessions`]: () => json(session(), 200) });

    await user.click(await screen.findByRole("button", { name: "Start interview" }));

    expect(await screen.findByText("Resuming your session…")).toBeInTheDocument();
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/interviews/${SESSION}`));
    expect(api.callsTo("POST", "/interview-sessions")).toHaveLength(1);
  });

  it("does not say it is resuming when a new session was made", async () => {
    const user = userEvent.setup();
    setup();

    await user.click(await screen.findByRole("button", { name: "Start interview" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/interviews/${SESSION}`));
    expect(screen.queryByText("Resuming your session…")).not.toBeInTheDocument();
  });

  it("says the job is gone when it cannot be found", async () => {
    setup({ [`GET /jobs/${JOB}`]: () => json({ status: 404, code: "job_not_found" }, 404) });

    expect(await screen.findByRole("alert")).toHaveTextContent(/no longer available/);
  });
});
