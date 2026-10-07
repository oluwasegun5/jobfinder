import { describe, expect, it } from "vitest";

import { MAX_LENGTH, redact } from "./redact";

describe("redact", () => {
  it.each([
    ["failed for ada.lovelace+jobs@example.co.uk", "ada.lovelace"],
    ["Authorization: Bearer abc.DEF-123_xyz", "abc.DEF"],
    ["key sk-ant-api03-AbCdEfGh1234567890 used", "AbCdEfGh"],
    ["stripe sk_live_51HabcdefgHIJK", "51Habc"],
    ["password=hunter2hunter2", "hunter2"],
    ['{"api_key": "hunter2hunter2"}', "hunter2"],
    ["X-Service-Token: hunter2hunter2", "hunter2"],
    ["token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl", "eyJhbGci"],
    ["refresh_token=hunter2hunter2; Path=/", "hunter2"],
    ["call +234 803 123 4567", "803 123"],
  ])("masks %s", (text, secret) => {
    expect(redact(text)).not.toContain(secret);
  });

  it("keeps the name of a secret field and says it was masked", () => {
    expect(redact("password=hunter2hunter2")).toBe("password=[redacted]");
  });

  it("leaves ordinary text alone", () => {
    const text = "GET /jobs 200 in 31ms at 2026-10-07T06:50:12Z for run 7d9f3c1e-4b2a-4c1d-9e8f-0a1b2c3d4e5f";
    expect(redact(text)).toBe(text);
  });

  it("cuts an overlong message", () => {
    const out = redact("cv ".repeat(5000));
    expect(out.endsWith("[truncated]")).toBe(true);
    expect(out.length).toBeLessThan(MAX_LENGTH + 50);
  });

  it("passes the empty string through", () => {
    expect(redact("")).toBe("");
  });
});
