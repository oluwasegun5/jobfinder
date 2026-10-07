import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { isValidTraceparent } from "@/lib/observability/trace-context";

import { proxy } from "./proxy";

const INCOMING = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

/** Next marks the request headers a proxy sets as x-middleware-request-<name> on the response. */
function forwarded(response: Response, name: string) {
  return response.headers.get(`x-middleware-request-${name}`);
}

describe("proxy trace propagation", () => {
  it("starts a trace for a browser call to core-api", () => {
    const response = proxy(new NextRequest("http://localhost:3000/api/core/jobs"));
    expect(isValidTraceparent(forwarded(response, "traceparent"))).toBe(true);
  });

  it("continues a trace the caller sent", () => {
    const response = proxy(
      new NextRequest("http://localhost:3000/api/core/jobs", { headers: { traceparent: INCOMING } }),
    );
    expect(forwarded(response, "traceparent")).toBe(INCOMING);
  });

  it("replaces a malformed traceparent instead of forwarding it", () => {
    const response = proxy(
      new NextRequest("http://localhost:3000/api/core/jobs", { headers: { traceparent: "junk" } }),
    );
    const value = forwarded(response, "traceparent");
    expect(value).not.toBe("junk");
    expect(isValidTraceparent(value)).toBe(true);
  });

  it("still blocks the operational core-api paths, Prometheus included", () => {
    for (const path of ["/api/core/actuator/prometheus", "/api/core/internal/v1/embeddings"]) {
      expect(proxy(new NextRequest(`http://localhost:3000${path}`)).status).toBe(404);
    }
  });
});
