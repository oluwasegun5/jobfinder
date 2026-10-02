import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fakeApi, json } from "@/test/fake-api";
import { renderWithQueryClient } from "@/test/render";

import { FEEDBACK, SESSION, SUMMARY, answerTurn, question, session } from "./fixtures";
import { InterviewSession } from "./session-view";

const hoisted = vi.hoisted(() => ({ client: undefined as unknown }));
vi.mock("@/lib/api", () => ({
  get api() {
    return hoisted.client;
  },
}));

const ANSWER = "I built a small prototype of both options and measured them.";
const Q1 = "Tell me about a time you led a project.";
const Q2 = "Describe a time you had to learn a new tool quickly.";

const afterFirstAnswer = () =>
  session({
    turnsAnswered: 1,
    creditsConsumed: 7,
    openQuestion: question(2, Q2),
    turns: [question(0, Q1), answerTurn(1, ANSWER), question(2, Q2)],
  });

function setup(routes: Parameters<typeof fakeApi>[0] = {}, initial: unknown = session()) {
  const api = fakeApi({ [`GET /interview-sessions/${SESSION}`]: () => json(initial), ...routes });
  hoisted.client = api.client;
  renderWithQueryClient(<InterviewSession id={SESSION} />);
  return api;
}

const answerPath = `/interview-sessions/${SESSION}/answers`;

beforeEach(() => {
  hoisted.client = undefined;
});

describe("InterviewSession: the open question", () => {
  it("shows the job, the interviewer, the credits, the progress and the question", async () => {
    setup();

    expect(await screen.findByRole("heading", { level: 1, name: /Backend Engineer/ })).toBeInTheDocument();
    expect(screen.getByText(/Interviewer: Engineering manager, direct tone/)).toBeInTheDocument();
    expect(screen.getByText("Credits used: 4")).toBeInTheDocument();
    expect(screen.getByText("Question 1 of 3")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Interview progress" })).toHaveAttribute("aria-valuenow", "0");
    expect(screen.getByTestId("open-question")).toHaveTextContent(Q1);
    expect(screen.getByRole("button", { name: "Send answer" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: /End interview/ })).not.toBeInTheDocument();
  });

  it("counts the characters and refuses an answer over the limit", async () => {
    const user = userEvent.setup();
    setup();
    const box = await screen.findByLabelText("Your answer");

    await user.type(box, "hello");
    expect(screen.getByText("5 / 4000")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send answer" })).toBeEnabled();

    await user.clear(box);
    await user.click(box);
    await user.paste("a".repeat(4001));
    expect(screen.getByText(/4001 \/ 4000/)).toHaveTextContent("too long");
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("button", { name: "Send answer" })).toBeDisabled();
  });
});

describe("InterviewSession: answering", () => {
  it("sends the answer with an idempotency key, shows the scoring state, then the feedback and the next question", async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => (release = resolve));
    const api = setup({
      [`POST ${answerPath}`]: async () => {
        await held;
        return json({ turn: answerTurn(1, ANSWER), nextQuestion: question(2, Q2), session: afterFirstAnswer() });
      },
    });

    await user.type(await screen.findByLabelText("Your answer"), ANSWER);
    await user.click(screen.getByRole("button", { name: "Send answer" }));

    expect(await screen.findByRole("button", { name: /Scoring your answer/ })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent(/Scoring your answer/);
    release();

    // The feedback of the answer: the four rubric scores, the STAR check and the quote.
    const card = await screen.findByLabelText("Feedback on answer 1");
    expect(within(card).getByText("Structure")).toBeInTheDocument();
    expect(within(card).getByLabelText("Structure: 4 out of 5")).toBeInTheDocument();
    expect(within(card).getByLabelText("Relevance: 5 out of 5")).toBeInTheDocument();
    expect(within(card).getByLabelText("Specificity: 3 out of 5")).toBeInTheDocument();
    expect(within(card).getByLabelText("Overall: 4 out of 5")).toBeInTheDocument();
    expect(within(card).getByText(/STAR check/)).toBeInTheDocument();
    expect(within(card).getByText("Result")).toBeInTheDocument();
    expect(within(card).getByText(/is missing from your answer/)).toBeInTheDocument();
    expect(within(card).getByText(FEEDBACK.strengths[0].quote)).toBeInTheDocument();

    // The next question is open, the box is empty, and the progress moved.
    expect(screen.getByTestId("open-question")).toHaveTextContent(Q2);
    expect(screen.getByLabelText("Your answer")).toHaveValue("");
    expect(screen.getByText("Question 2 of 3")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Interview progress" })).toHaveAttribute("aria-valuenow", "1");
    expect(screen.getByText("Credits used: 7")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /End interview and get summary/ })).toBeInTheDocument();

    const body = api.callsTo("POST", answerPath)[0].body as { answer: string; idempotencyKey: string };
    expect(body.answer).toBe(ANSWER);
    expect(body.idempotencyKey).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
  });

  it("keeps the text and retries with the same key after the interviewer was unavailable", async () => {
    const user = userEvent.setup();
    let calls = 0;
    const api = setup({
      [`POST ${answerPath}`]: () => {
        calls += 1;
        return calls === 1
          ? json({ status: 503, code: "mock_interview_unavailable", detail: "down" }, 503)
          : json({ turn: answerTurn(1, ANSWER), nextQuestion: question(2, Q2), session: afterFirstAnswer() });
      },
    });

    await user.type(await screen.findByLabelText("Your answer"), ANSWER);
    await user.click(screen.getByRole("button", { name: "Send answer" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/interviewer is unavailable right now/);
    expect(screen.getByLabelText("Your answer")).toHaveValue(ANSWER);
    await user.click(within(alert).getByRole("button", { name: "Try again" }));

    await screen.findByLabelText("Feedback on answer 1");
    const [first, second] = api.callsTo("POST", answerPath).map((c) => c.body as { idempotencyKey: string });
    expect(second.idempotencyKey).toBe(first.idempotencyKey);
  });

  it("uses a new key when the text was changed after a failure", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST ${answerPath}`]: () => json({ status: 503, code: "mock_interview_unavailable" }, 503) });

    const box = await screen.findByLabelText("Your answer");
    await user.type(box, "one");
    await user.click(screen.getByRole("button", { name: "Send answer" }));
    await screen.findByRole("alert");
    await user.type(box, " two");
    await user.click(screen.getByRole("button", { name: "Send answer" }));
    await waitFor(() => expect(api.callsTo("POST", answerPath)).toHaveLength(2));

    const [first, second] = api.callsTo("POST", answerPath).map((c) => c.body as { idempotencyKey: string });
    expect(second.idempotencyKey).not.toBe(first.idempotencyKey);
  });

  it("says the daily allowance is used up, keeps the text and offers no retry", async () => {
    const user = userEvent.setup();
    setup({ [`POST ${answerPath}`]: () => json({ status: 429, code: "ai_daily_cap_reached", resetsAt: "2026-10-03T00:00:00Z" }, 429) });

    await user.type(await screen.findByLabelText("Your answer"), ANSWER);
    await user.click(screen.getByRole("button", { name: "Send answer" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/used today's AI allowance/);
    expect(within(alert).queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Your answer")).toHaveValue(ANSWER);
  });

  it("says another answer is still being scored and offers to send again", async () => {
    const user = userEvent.setup();
    setup({ [`POST ${answerPath}`]: () => json({ status: 409, code: "answer_in_flight" }, 409) });

    await user.type(await screen.findByLabelText("Your answer"), ANSWER);
    await user.click(screen.getByRole("button", { name: "Send answer" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/still being scored/);
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("never writes the answer to browser storage", async () => {
    const user = userEvent.setup();
    const local = vi.spyOn(Storage.prototype, "setItem");
    setup({ [`POST ${answerPath}`]: () => json({ turn: answerTurn(1, ANSWER), nextQuestion: question(2, Q2), session: afterFirstAnswer() }) });

    await user.type(await screen.findByLabelText("Your answer"), ANSWER);
    await user.click(screen.getByRole("button", { name: "Send answer" }));
    await screen.findByLabelText("Feedback on answer 1");

    const written = local.mock.calls.map((c) => String(c[1]));
    expect(written.some((v) => v.includes("prototype"))).toBe(false);
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    local.mockRestore();
  });
});

describe("InterviewSession: finishing", () => {
  const completed = () =>
    session({
      status: "COMPLETED",
      turnsAnswered: 2,
      creditsConsumed: 11.5,
      openQuestion: undefined,
      completedAt: "2026-10-02T10:20:00Z",
      summary: SUMMARY,
      turns: [question(0, Q1), answerTurn(1, ANSWER), question(2, Q2), answerTurn(3, "Second answer.")],
    });

  it("shows the summary when the last answer completes the interview", async () => {
    const user = userEvent.setup();
    setup(
      { [`POST ${answerPath}`]: () => json({ turn: answerTurn(3, "Second answer."), summary: SUMMARY, session: completed() }) },
      session({ turnsAnswered: 1, openQuestion: question(2, Q2), turns: [question(0, Q1), answerTurn(1, ANSWER), question(2, Q2)] }),
    );

    await user.type(await screen.findByLabelText("Your answer"), "Second answer.");
    await user.click(screen.getByRole("button", { name: "Send answer" }));

    const summary = await screen.findByLabelText("Interview summary");
    expect(within(summary).getByText(SUMMARY.narrative)).toBeInTheDocument();
    expect(within(summary).getByText("You back claims with a measurement.")).toBeInTheDocument();
    expect(within(summary).getByText("Finish with the result.")).toBeInTheDocument();
    expect(within(summary).getByText("Prepare two stories with numbers.")).toBeInTheDocument();
    expect(within(summary).getByLabelText("Overall: 4 out of 5")).toBeInTheDocument();
    expect(within(summary).getByLabelText("STAR completeness: 4 out of 5")).toBeInTheDocument();
    expect(within(summary).getByText(/used 11.5 credits in total/)).toBeInTheDocument();
    expect(within(summary).getByRole("link", { name: "Practise again" })).toHaveAttribute("href", `/jobs/${completed().jobId}/interview`);
    expect(screen.queryByLabelText("Your answer")).not.toBeInTheDocument();
    // The feedback on the last answer is still open to read beside the summary.
    expect(screen.getByLabelText("Feedback on answer 2")).toBeVisible();
  });

  it("ends the interview early on request", async () => {
    const user = userEvent.setup();
    const api = setup({ [`POST /interview-sessions/${SESSION}/complete`]: () => json(completed()) }, afterFirstAnswer());

    await user.click(await screen.findByRole("button", { name: /End interview and get summary/ }));

    expect(await screen.findByLabelText("Interview summary")).toBeInTheDocument();
    expect(api.callsTo("POST", `/interview-sessions/${SESSION}/complete`)).toHaveLength(1);
  });

  it("offers to make the summary again when the last answer was scored but the summary was not made", async () => {
    const user = userEvent.setup();
    const api = setup(
      { [`POST /interview-sessions/${SESSION}/complete`]: () => json(completed()) },
      session({ turnsAnswered: 3, openQuestion: undefined, turns: [question(0, Q1), answerTurn(1, ANSWER)] }),
    );

    expect(await screen.findByText(/Your summary is not ready yet/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Your answer")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Get my summary" }));

    expect(await screen.findByLabelText("Interview summary")).toBeInTheDocument();
    expect(api.callsTo("POST", `/interview-sessions/${SESSION}/complete`)).toHaveLength(1);
  });

  it("says an abandoned interview was closed, keeps the answers and has no answer box", async () => {
    setup(
      {},
      session({
        status: "ABANDONED",
        turnsAnswered: 1,
        openQuestion: undefined,
        turns: [question(0, Q1), answerTurn(1, ANSWER), question(2, Q2)],
      }),
    );

    expect(await screen.findByText(/left idle for too long and was closed/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Start a new interview" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Your answer")).not.toBeInTheDocument();
    expect(screen.getByText(ANSWER)).toBeInTheDocument();
  });

  it("says when the interview cannot be loaded", async () => {
    setup({ [`GET /interview-sessions/${SESSION}`]: () => json({ status: 404, code: "interview_session_not_found" }, 404) });

    expect(await screen.findByRole("alert")).toHaveTextContent(/does not exist, or it is not yours/);
    expect(screen.getByRole("link", { name: "Back to your interviews" })).toHaveAttribute("href", "/interviews");
  });
});
