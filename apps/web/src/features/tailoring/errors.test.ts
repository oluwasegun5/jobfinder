import { describe, expect, it } from "vitest";

import { ApiProblem } from "@/features/profile/queries";

import { capMessage, toFailure } from "./errors";

const problem = (status: number, body: Record<string, unknown>) => new ApiProblem(body, status);

describe("toFailure", () => {
  it("maps the daily cap to a message with the reset time and no retry", () => {
    const failure = toFailure(problem(429, { code: "ai_daily_cap_reached", detail: "raw", resetsAt: "2026-10-03T00:00:00Z" }));
    expect(failure.kind).toBe("cap");
    expect(failure.resetsAt).toBe("2026-10-03T00:00:00Z");
    expect(failure.retryable).toBe(false);
    expect(failure.message).toBe(capMessage("2026-10-03T00:00:00Z"));
    expect(failure.message).toMatch(/Oct/);
    expect(failure.message).not.toMatch(/raw/);
  });

  it("treats a bare 429 as the cap too", () => {
    expect(toFailure(problem(429, {})).kind).toBe("cap");
  });

  it("uses words for known codes, and marks 503 as retryable", () => {
    expect(toFailure(problem(409, { code: "invalid_transition" })).message).toMatch(/cannot go back to Saved/);
    expect(toFailure(problem(409, { code: "not_applied_yet" })).kind).toBe("conflict");
    const unavailable = toFailure(problem(503, { code: "follow_up_unavailable" }));
    expect(unavailable.kind).toBe("unavailable");
    expect(unavailable.retryable).toBe(true);
    expect(toFailure(problem(409, { code: "resume_required" })).message).toMatch(/CV/);
  });

  it("falls back to the server detail, then to the caller's text", () => {
    expect(toFailure(problem(400, { code: "weird", detail: "Specific detail." })).message).toBe("Specific detail.");
    expect(toFailure(problem(400, {}), "Fallback.").message).toBe("Fallback.");
  });

  it("explains a network failure", () => {
    const failure = toFailure(new TypeError("fetch failed"));
    expect(failure.kind).toBe("network");
    expect(failure.retryable).toBe(true);
  });

  it("never carries request data into the message", () => {
    const failure = toFailure(problem(404, { code: "document_not_found", detail: "Document not found.", title: "secret@example.test" }));
    expect(JSON.stringify(failure)).not.toMatch(/secret@/);
  });
});
