import { describe, expect, it } from "vitest";

import { ApiProblem } from "@/features/profile/queries";

import { interviewFailure } from "./errors";
import { FEEDBACK, answerTurn, question, session } from "./fixtures";
import {
  ANSWER_MAX_CHARS,
  canSubmit,
  createKeyKeeper,
  exchanges,
  formatCredits,
  formatScore,
  isOver,
  newIdempotencyKey,
  personaLine,
  progressOf,
} from "./model";

describe("idempotency keys", () => {
  it("makes keys core-api accepts", () => {
    for (let i = 0; i < 20; i++) expect(newIdempotencyKey()).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
  });

  it("keeps the key while the text is the same and changes it when the text changes or after a success", () => {
    const keeper = createKeyKeeper();
    const first = keeper.keyFor("my answer");
    expect(keeper.keyFor("my answer")).toBe(first);
    const edited = keeper.keyFor("my answer, edited");
    expect(edited).not.toBe(first);
    keeper.reset();
    expect(keeper.keyFor("my answer, edited")).not.toBe(edited);
  });
});

describe("answers", () => {
  it("needs some text and no more than the limit", () => {
    expect(canSubmit("")).toBe(false);
    expect(canSubmit("   \n ")).toBe(false);
    expect(canSubmit("a")).toBe(true);
    expect(canSubmit("a".repeat(ANSWER_MAX_CHARS))).toBe(true);
    expect(canSubmit("a".repeat(ANSWER_MAX_CHARS + 1))).toBe(false);
  });

  it("counts characters, not UTF-16 units", () => {
    expect(canSubmit("😀".repeat(ANSWER_MAX_CHARS))).toBe(true);
    expect(canSubmit("😀".repeat(ANSWER_MAX_CHARS + 1))).toBe(false);
  });
});

describe("the session", () => {
  it("reports progress through the turns and never past the end", () => {
    expect(progressOf(session({ turnsAnswered: 0 }) as never)).toEqual({ answered: 0, total: 3, current: 1, percent: 0 });
    expect(progressOf(session({ turnsAnswered: 2 }) as never)).toEqual({ answered: 2, total: 3, current: 3, percent: 67 });
    expect(progressOf(session({ turnsAnswered: 3 }) as never).current).toBe(3);
    expect(progressOf(session({ turnsAnswered: 9 }) as never).answered).toBe(3);
  });

  it("is over when completed or abandoned", () => {
    expect(isOver(session() as never)).toBe(false);
    expect(isOver(session({ status: "COMPLETED" }) as never)).toBe(true);
    expect(isOver(session({ status: "ABANDONED" }) as never)).toBe(true);
    expect(isOver(undefined)).toBe(false);
  });

  it("pairs each question with its answer and leaves the open question unanswered", () => {
    const turns = [question(0, "Q1"), answerTurn(1, "A1"), question(2, "Q2")];
    const pairs = exchanges(turns as never);
    expect(pairs).toHaveLength(2);
    expect(pairs[0].answer?.content).toBe("A1");
    expect(pairs[0].answer?.feedback).toEqual(FEEDBACK);
    expect(pairs[1].answer).toBeUndefined();
  });

  it("describes the persona and formats numbers", () => {
    expect(personaLine(session().persona as never)).toBe("Engineering manager, direct tone");
    expect(formatScore(4)).toBe("4");
    expect(formatScore(3.5)).toBe("3.5");
    expect(formatScore(undefined)).toBe("n/a");
    expect(formatCredits(0)).toBe("0");
    expect(formatCredits(11.5)).toBe("11.5");
    expect(formatCredits("7.000000")).toBe("7");
  });
});

describe("interviewFailure", () => {
  const problem = (status: number, body: Record<string, unknown>) => new ApiProblem(body, status);

  it("explains the daily cap and says when it resets", () => {
    const f = interviewFailure(problem(429, { code: "ai_daily_cap_reached", resetsAt: "2026-10-03T00:00:00Z" }));
    expect(f.kind).toBe("cap");
    expect(f.message).toContain("today's AI allowance");
    expect(f.retryable).toBe(false);
  });

  it("explains an answer already in flight as retryable, and that the text is kept", () => {
    const f = interviewFailure(problem(409, { code: "answer_in_flight" }));
    expect(f.kind).toBe("conflict");
    expect(f.retryable).toBe(true);
    expect(f.message).toMatch(/still being scored/);
  });

  it("explains an unavailable interviewer on any 503 and never repeats request data", () => {
    const f = interviewFailure(problem(503, { code: "mock_interview_unavailable", detail: "secret answer text" }));
    expect(f.kind).toBe("unavailable");
    expect(f.retryable).toBe(true);
    expect(f.message).not.toContain("secret");
    expect(interviewFailure(problem(503, { code: "something_else" })).message).toMatch(/unavailable right now/);
  });

  it("has words for finished and abandoned sessions and not retryable", () => {
    expect(interviewFailure(problem(409, { code: "interview_session_completed" })).retryable).toBe(false);
    expect(interviewFailure(problem(409, { code: "interview_session_abandoned" })).message).toMatch(/idle/);
  });

  it("treats a network failure as retryable", () => {
    const f = interviewFailure(new TypeError("fetch failed"));
    expect(f.kind).toBe("network");
    expect(f.retryable).toBe(true);
  });
});
