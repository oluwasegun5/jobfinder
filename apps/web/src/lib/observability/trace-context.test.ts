import { describe, expect, it } from "vitest";

import { isValidTraceparent, newTraceparent, traceIdOf, traceparentToForward } from "./trace-context";

const INCOMING = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
const ACTIVE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

describe("trace context", () => {
  it("makes valid, distinct traceparents", () => {
    const a = newTraceparent();
    expect(isValidTraceparent(a)).toBe(true);
    expect(newTraceparent()).not.toBe(a);
  });

  it.each([
    [undefined],
    [""],
    ["garbage"],
    ["00-00000000000000000000000000000000-00f067aa0ba902b7-01"],
    ["00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"],
    ["01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"],
    ["00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"],
  ])("rejects %s", (value) => {
    expect(isValidTraceparent(value)).toBe(false);
  });

  it("reads the trace id", () => {
    expect(traceIdOf(INCOMING)).toBe("4bf92f3577b34da6a3ce929d0e0e4736");
  });

  it("prefers the active span, then the caller's trace, then a new one", () => {
    expect(traceparentToForward(INCOMING, ACTIVE)).toBe(ACTIVE);
    expect(traceparentToForward(INCOMING, undefined)).toBe(INCOMING);
    expect(isValidTraceparent(traceparentToForward(null, undefined))).toBe(true);
  });

  it("never forwards an invalid value", () => {
    const forwarded = traceparentToForward("junk", "also junk");
    expect(isValidTraceparent(forwarded)).toBe(true);
    expect(forwarded).not.toContain("junk");
  });
});
