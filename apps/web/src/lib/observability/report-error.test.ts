import type { ErrorEvent } from "@sentry/node";
import { describe, expect, it } from "vitest";

import { reportRequestError, scrubSentryEvent, startSentry } from "./report-error";

describe("reportRequestError", () => {
  it("writes one JSON line with the trace id and no personal data", () => {
    const lines: string[] = [];
    const error = new Error("lookup failed for ada@example.test with Bearer abc.def.ghi");
    reportRequestError(
      error,
      {
        path: "/jobs?email=ada@example.test",
        method: "GET",
        routePath: "/jobs",
        routeType: "render",
        traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      },
      (line) => lines.push(line),
    );

    expect(lines).toHaveLength(1);
    const entry = JSON.parse(lines[0]);
    expect(entry.traceId).toBe("4bf92f3577b34da6a3ce929d0e0e4736");
    expect(entry.path).toBe("/jobs");
    expect(entry["service.name"]).toBe("web");
    expect(lines[0]).not.toContain("ada@");
    expect(lines[0]).not.toContain("abc.def.ghi");
  });
});

describe("scrubSentryEvent", () => {
  it("leaves nothing personal in the event", () => {
    const event = {
      type: undefined,
      request: { url: "http://x/?email=ada@example.test", data: "my CV" },
      user: { email: "ada@example.test" },
      server_name: "laptop",
      breadcrumbs: [{ message: "ada@example.test" }],
      extra: { cv: "my CV" },
      message: "failed for ada@example.test",
      exception: {
        values: [
          {
            type: "Error",
            value: "bad ada@example.test",
            stacktrace: { frames: [{ function: "f", vars: { cv: "my CV" } }] },
          },
        ],
      },
    } as unknown as ErrorEvent;

    const out = scrubSentryEvent(event);

    expect(out.request).toBeUndefined();
    expect(out.user).toBeUndefined();
    expect(out.extra).toBeUndefined();
    expect(out.breadcrumbs).toBeUndefined();
    expect(JSON.stringify(out)).not.toContain("ada@");
    expect(out.exception?.values?.[0].stacktrace?.frames?.[0].vars).toBeUndefined();
  });
});

describe("startSentry", () => {
  it("stays off without a DSN", () => {
    expect(startSentry({})).toBe(false);
    expect(startSentry({ SENTRY_DSN: "" })).toBe(false);
  });
});
