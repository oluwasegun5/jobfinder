import { createApiClient } from "@jobfinder/api-contract";
import { beforeEach, describe, expect, it, vi } from "vitest";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Loads fresh session/api modules (the bare refresh client captures global fetch at import). */
async function setup(handler: (request: Request) => Response | Promise<Response>) {
  vi.resetModules();
  const fetchMock = vi.fn(async (input: Request | string | URL, init?: RequestInit) =>
    handler(input instanceof Request ? input : new Request(new URL(String(input), "http://web.test"), init)),
  );
  vi.stubGlobal("fetch", fetchMock);
  const session = await import("@/lib/auth/session");
  const { authMiddleware } = await import("@/lib/api");
  const client = createApiClient({ baseUrl: "http://web.test/api/core", fetch: fetchMock });
  client.use(authMiddleware);
  return { session, client, fetchMock };
}

describe("auth middleware", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends the in-memory access token as a bearer header", async () => {
    const { session, client, fetchMock } = await setup(() => json({ id: "1" }));
    session.setSession({ accessToken: "abc", tokenType: "Bearer", expiresIn: 900 });

    await client.GET("/auth/me");

    expect(fetchMock.mock.calls[0][0]).toBeInstanceOf(Request);
    expect((fetchMock.mock.calls[0][0] as Request).headers.get("Authorization")).toBe("Bearer abc");
  });

  it("refreshes silently on 401 and replays the request once", async () => {
    const seen: string[] = [];
    const { session, client } = await setup((request) => {
      const url = new URL(request.url);
      seen.push(`${request.method} ${url.pathname} ${request.headers.get("Authorization") ?? ""}`.trim());
      if (url.pathname.endsWith("/auth/refresh")) {
        return json({ accessToken: "fresh", tokenType: "Bearer", expiresIn: 900 });
      }
      return request.headers.get("Authorization") === "Bearer fresh" ? json({ id: "1" }) : json({}, 401);
    });
    session.setSession({ accessToken: "stale", tokenType: "Bearer", expiresIn: 900 });

    const { data } = await client.GET("/auth/me");

    expect(data).toEqual({ id: "1" });
    expect(session.getAccessToken()).toBe("fresh");
    expect(seen).toEqual([
      "GET /api/core/auth/me Bearer stale",
      "POST /api/core/auth/refresh",
      "GET /api/core/auth/me Bearer fresh",
    ]);
  });

  it("clears the session when the refresh token is rejected", async () => {
    const { session, client } = await setup((request) => json({}, request.url.endsWith("/auth/refresh") ? 401 : 401));
    const listener = vi.fn();
    session.subscribeToSession(listener);
    session.setSession({ accessToken: "stale", tokenType: "Bearer", expiresIn: 900 });

    const { response } = await client.GET("/auth/me");

    expect(response.status).toBe(401);
    expect(session.getAccessToken()).toBeNull();
    expect(listener).toHaveBeenLastCalledWith(null);
  });

  it("shares one refresh request between concurrent callers", async () => {
    let refreshes = 0;
    const { session } = await setup(async () => {
      refreshes += 1;
      return json({ accessToken: "fresh", tokenType: "Bearer", expiresIn: 900 });
    });

    const tokens = await Promise.all([session.refreshSession(), session.refreshSession(), session.refreshSession()]);

    expect(tokens).toEqual(["fresh", "fresh", "fresh"]);
    expect(refreshes).toBe(1);
  });
});
