import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

import { STUB_PORT } from "../../playwright.config";

/** Builds the extension for the test: it talks to the local stub core-api, and its panel's shadow root is open. */
export default function globalSetup(): void {
  const origin = `http://127.0.0.1:${STUB_PORT}`;
  execFileSync(process.execPath, [resolve(import.meta.dirname, "../../build.mjs")], {
    stdio: "inherit",
    env: { ...process.env, OUT_DIR: "dist-e2e", PANEL_OPEN: "true", CORE_API_URL: `${origin}/api/core`, STORAGE_ORIGIN: origin },
  });
}
