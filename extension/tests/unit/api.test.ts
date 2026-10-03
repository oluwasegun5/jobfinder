import { beforeEach, describe, expect, it, vi } from "vitest";

import { authed, login, logout, refreshAccess, SignedOutError } from "../../src/background/api";
import { loadAuth, protectSession, saveAuth } from "../../src/background/session";
import { installFakeChrome, installFetch, json, type FakeStorage } from "./fake-chrome";

let storage: FakeStorage;

beforeEach(() => {
  vi.unstubAllGlobals();
  storage = installFakeChrome();
});

const token = (value: string, ttlMs = 600_000) => ({ accessToken: value, expiresAt: Date.now() + ttlMs, email: "sam@example.test" });
const AUTH = { accessToken: "new-token", tokenType: "Bearer", expiresIn: 900 };

describe("session storage", () => {
  it("restricts the session area to trusted contexts", async () => {
    await protectSession();
    expect(storage.accessLevel).toBe("TRUSTED_CONTEXTS");
  });

  it("returns null for anything that is not a stored token", async () => {
    storage.session.auth = { accessToken: 5 };
    expect(await loadAuth()).toBeNull();
    storage.session.auth = "x";
    expect(await loadAuth()).toBeNull();
  });
});

describe("login", () => {
  it("keeps the access token in chrome.storage.session and nowhere else", async () => {
    const calls = installFetch({ "POST /auth/login": () => json(200, AUTH) });

    expect(await login(" Sam@Example.test ", "correct horse")).toBe("ok");

    expect(storage.session.auth).toMatchObject({ accessToken: "new-token", email: "sam@example.test" });
    expect(storage.local).toEqual({});
    expect(calls[0]).toMatchObject({ method: "POST", credentials: "include", body: { email: " Sam@Example.test ", password: "correct horse" } });
    // The password is not stored anywhere.
    expect(JSON.stringify(storage)).not.toContain("correct horse");
  });

  it("stores nothing when the server says no, and tells apart a wrong password from an outage", async () => {
    installFetch({ "POST /auth/login": () => json(401, { code: "invalid_credentials" }) });
    expect(await login("a@example.test", "bad")).toBe("rejected");
    expect(storage.session).toEqual({});

    installFetch({ "POST /auth/login": () => json(503) });
    expect(await login("a@example.test", "bad")).toBe("failed");

    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    expect(await login("a@example.test", "bad")).toBe("failed");
    expect(storage.session).toEqual({});
  });
});

describe("calls to core-api", () => {
  it("send the token as a bearer header and never ask the browser for cookies", async () => {
    await saveAuth(token("t-1"));
    const calls = installFetch({ "GET /profile": () => json(200, { fullName: "Sam Example" }) });

    const result = await authed((api) => api.GET("/profile"));

    expect(result).toEqual({ data: { fullName: "Sam Example" }, status: 200 });
    expect(calls[0]).toMatchObject({ authorization: "Bearer t-1", credentials: "omit" });
  });

  it("refreshes silently first when the token has expired, using the cookie", async () => {
    await saveAuth(token("old", -1000));
    const calls = installFetch({
      "POST /auth/refresh": () => json(200, AUTH),
      "GET /profile": () => json(200, {}),
    });

    await authed((api) => api.GET("/profile"));

    expect(calls.map((c) => `${c.method} ${c.url.pathname}`)).toEqual(["POST /auth/refresh", "GET /profile"]);
    expect(calls[0]).toMatchObject({ credentials: "include", authorization: null });
    expect(calls[1]!.authorization).toBe("Bearer new-token");
    expect(storage.session.auth).toMatchObject({ accessToken: "new-token", email: "sam@example.test" });
  });

  it("refreshes once and retries when the server answers 401", async () => {
    await saveAuth(token("stale"));
    let profileCalls = 0;
    const calls = installFetch({
      "POST /auth/refresh": () => json(200, AUTH),
      "GET /profile": ({ authorization }) => {
        profileCalls += 1;
        return authorization === "Bearer new-token" ? json(200, { fullName: "Sam" }) : json(401);
      },
    });

    const result = await authed((api) => api.GET("/profile"));

    expect(result.status).toBe(200);
    expect(profileCalls).toBe(2);
    expect(calls.filter((c) => c.url.pathname === "/auth/refresh")).toHaveLength(1);
  });

  it("asks the user to sign in again, and clears the session, when the refresh does not work", async () => {
    await saveAuth(token("old", -1000));
    installFetch({ "POST /auth/refresh": () => json(401, { code: "invalid_refresh_token" }) });

    await expect(authed((api) => api.GET("/profile"))).rejects.toBeInstanceOf(SignedOutError);
    expect(storage.session).toEqual({});
  });

  it("treats a refresh that cannot reach the server like a failed refresh", async () => {
    await saveAuth(token("old", -1000));
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));

    await expect(authed((api) => api.GET("/profile"))).rejects.toBeInstanceOf(SignedOutError);
    expect(storage.session).toEqual({});
  });

  it("signs out when a call is still 401 after a successful refresh", async () => {
    await saveAuth(token("t"));
    installFetch({ "POST /auth/refresh": () => json(200, AUTH), "GET /profile": () => json(401) });

    await expect(authed((api) => api.GET("/profile"))).rejects.toBeInstanceOf(SignedOutError);
  });

  it("shares one refresh between simultaneous calls (a rotating refresh token cannot be spent twice)", async () => {
    await saveAuth(token("old", -1000));
    let refreshes = 0;
    installFetch({
      "POST /auth/refresh": async () => {
        refreshes += 1;
        await new Promise((r) => setTimeout(r, 20));
        return json(200, AUTH);
      },
      "GET /profile": () => json(200, {}),
      "GET /resumes": () => json(200, []),
    });

    await Promise.all([authed((api) => api.GET("/profile")), authed((api) => api.GET("/resumes")), refreshAccess()]);

    expect(refreshes).toBe(1);
  });

  it("is signed out when there is no session and no refresh cookie", async () => {
    installFetch({ "POST /auth/refresh": () => json(401) });
    await expect(authed((api) => api.GET("/profile"))).rejects.toBeInstanceOf(SignedOutError);
  });
});

describe("logout", () => {
  it("tells the server, with the cookie, and clears the session", async () => {
    await saveAuth(token("t"));
    const calls = installFetch({ "POST /auth/logout": () => new Response(null, { status: 204 }) });

    await logout();

    expect(calls[0]).toMatchObject({ method: "POST", credentials: "include" });
    expect(storage.session).toEqual({});
  });

  it("clears the session even when the server cannot be reached", async () => {
    await saveAuth(token("t"));
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));

    await logout();

    expect(storage.session).toEqual({});
  });
});
