import { describe, expect, it } from "vitest";

import { contentSecurityPolicy, isBlockedCorePath } from "./edge";

describe("isBlockedCorePath", () => {
  it.each([
    "/api/core/internal/embeddings",
    "/api/core/internal",
    "/api/core/INTERNAL/x",
    "/api/core/%69nternal/x",
    "/api/core/%2569nternal/x",
    "/api/core//internal/x",
    "/api/core/./internal/x",
    "/api/core/jobs/../internal/x",
    "/api/core/internal;a=b/x",
    "/api/core/actuator/health",
    "/api/core/%zz",
  ])("blocks %s", (path) => {
    expect(isBlockedCorePath(path)).toBe(true);
  });

  it.each([
    "/api/core/jobs",
    "/api/core/auth/login",
    "/api/core/internalish",
    "/api/core/jobs/internal",
    "/internal/anything",
    "/dashboard",
  ])("lets %s through", (path) => {
    expect(isBlockedCorePath(path)).toBe(false);
  });
});

describe("contentSecurityPolicy", () => {
  it("allows scripts only with the nonce and never unsafe-inline or unsafe-eval in production", () => {
    const csp = contentSecurityPolicy("abc", false);
    const script = csp.split("; ").find((d) => d.startsWith("script-src"))!;
    expect(script).toContain("'nonce-abc'");
    expect(script).not.toContain("unsafe-inline");
    expect(script).not.toContain("unsafe-eval");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("base-uri 'none'");
    expect(csp).toContain("connect-src 'self'");
  });

  it("allows eval only in development", () => {
    expect(contentSecurityPolicy("abc", true)).toContain("'unsafe-eval'");
  });
});
