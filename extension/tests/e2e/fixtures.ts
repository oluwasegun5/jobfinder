import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

import { chromium, test as base, type BrowserContext, type Page, type Worker } from "@playwright/test";

import { STUB_PORT } from "../../playwright.config";
import { CREDENTIALS, Stub } from "./stub-core-api";

export const GREENHOUSE_URL = "https://boards.greenhouse.io/exampleco/jobs/4012345";
export const LEVER_URL = "https://jobs.lever.co/exampleco/5f2c0a7e-1d3b-4c58-9a7e-0b1c2d3e4f50/apply";

const fixturesDir = resolve(import.meta.dirname, "../fixtures");

interface Fixtures {
  stub: Stub;
  context: BrowserContext;
  worker: Worker;
  extensionId: string;
  /** Signs in through the toolbar popup page, as a user would. */
  signIn: () => Promise<void>;
  /** Opens a fixture page at its real ATS address (served from disk, nothing leaves the machine). */
  openAts: (url: string, fixture: string) => Promise<Page>;
}

export const test = base.extend<Fixtures, { stubServer: Stub }>({
  stubServer: [
    // eslint-disable-next-line no-empty-pattern -- Playwright requires a destructuring pattern as the first argument
    async ({}, use) => {
      const stub = new Stub(STUB_PORT);
      await stub.start();
      await use(stub);
      await stub.stop();
    },
    { scope: "worker" },
  ],
  stub: async ({ stubServer }, use) => {
    stubServer.reset();
    await use(stubServer);
  },
  // eslint-disable-next-line no-empty-pattern -- Playwright requires a destructuring pattern as the first argument
  context: async ({}, use) => {
    const userDataDir = await mkdtemp(resolve(tmpdir(), "jf-ext-e2e-"));
    const extensionPath = resolve(import.meta.dirname, "../../dist-e2e");
    const context = await chromium.launchPersistentContext(userDataDir, {
      channel: "chromium",
      headless: true,
      args: [`--disable-extensions-except=${extensionPath}`, `--load-extension=${extensionPath}`],
    });
    await use(context);
    await context.close();
    await rm(userDataDir, { recursive: true, force: true });
  },
  worker: async ({ context }, use) => {
    const worker = context.serviceWorkers()[0] ?? (await context.waitForEvent("serviceworker"));
    await use(worker);
  },
  extensionId: async ({ worker }, use) => {
    await use(new URL(worker.url()).host);
  },
  signIn: async ({ context, extensionId, stub }, use) => {
    await use(async () => {
      const popup = await context.newPage();
      await popup.goto(`chrome-extension://${extensionId}/popup.html`);
      await popup.locator("#email").fill(CREDENTIALS.email);
      await popup.locator("#password").fill(CREDENTIALS.password);
      await popup.locator("#sign-in").click();
      await popup.locator("#signed-in").waitFor({ state: "visible", timeout: 8000 }).catch(async (e) => {
        throw new Error(`sign-in failed: status="${await popup.locator("#status").innerText()}" stub saw ${JSON.stringify(stub.requests.map((r) => `${r.method} ${r.path}`))}; ${String(e)}`);
      });
      await popup.close();
      if (stub.count("POST", "/auth/login") === 0) throw new Error("sign-in did not reach the stub");
    });
  },
  openAts: async ({ context, stub }, use) => {
    // Catch-all first: anything the page asks for that is not the fixture is refused, so no real ATS is contacted.
    let guarded = false;
    await use(async (url, fixture) => {
      if (!guarded) {
        guarded = true;
        // Only web pages other than the stub core-api: the extension's own pages (chrome-extension://) must load.
        await context.route((url) => /^https?:$/.test(url.protocol) && url.origin !== stub.origin, (route) => void route.abort());
      }
      const html = await readFile(resolve(fixturesDir, fixture), "utf8");
      const page = await context.newPage();
      await page.route(url, (route) => route.fulfill({ status: 200, contentType: "text/html", body: html }));
      await page.goto(url);
      return page;
    });
  },
});

export { expect } from "@playwright/test";
