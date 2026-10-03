import { beforeEach, describe, expect, it, vi } from "vitest";

import { installFakeChrome, installFetch, json } from "./fake-chrome";

type Listener = (message: unknown, sender: Record<string, unknown>, sendResponse: (r: unknown) => void) => boolean;

async function loadRouter(): Promise<Listener> {
  vi.resetModules();
  installFakeChrome();
  let listener: Listener | undefined;
  const chromeGlobal = (globalThis as unknown as { chrome: { runtime: Record<string, unknown> } }).chrome;
  chromeGlobal.runtime.getURL = (path: string) => `chrome-extension://test-extension/${path}`;
  chromeGlobal.runtime.onInstalled = { addListener: () => undefined };
  chromeGlobal.runtime.onMessage = { addListener: (fn: Listener) => (listener = fn) };
  await import("../../src/background/index");
  if (!listener) throw new Error("the router registered no listener");
  return listener;
}

function ask(listener: Listener, message: unknown, sender: Record<string, unknown>): Promise<unknown> {
  return new Promise((resolve) => {
    const kept = listener(message, sender, resolve);
    if (!kept) resolve("ignored");
  });
}

describe("message router", () => {
  beforeEach(() => {
    installFetch({ "POST /auth/login": () => json(200, { accessToken: "t", expiresIn: 900 }) });
  });

  it("accepts a sign-in from the extension's own popup page, whether or not it has a tab", async () => {
    const router = await loadRouter();
    const message = { type: "login", email: "sam.example@example.test", password: "pw" };
    expect(await ask(router, message, { id: "test-extension", url: "chrome-extension://test-extension/popup.html" })).toMatchObject({ ok: true });
    expect(
      await ask(router, message, { id: "test-extension", url: "chrome-extension://test-extension/popup.html", tab: { id: 3 } }),
    ).toMatchObject({ ok: true });
  });

  it("refuses a sign-in relayed from a content script on a web page", async () => {
    const router = await loadRouter();
    const message = { type: "login", email: "a@b.test", password: "pw" };
    const answer = await ask(router, message, { id: "test-extension", url: "https://boards.greenhouse.io/x/jobs/1", tab: { id: 3 } });
    expect(answer).toMatchObject({ ok: false, message: "Sign in from the toolbar popup." });
  });

  it("ignores messages from other extensions", async () => {
    const router = await loadRouter();
    expect(await ask(router, { type: "status" }, { id: "someone-else", url: "chrome-extension://someone-else/x.html" })).toBe("ignored");
  });
});
