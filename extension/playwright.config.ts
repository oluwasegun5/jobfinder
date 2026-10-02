import { defineConfig } from "@playwright/test";

export const STUB_PORT = 18787;

export default defineConfig({
  testDir: "tests/e2e",
  testMatch: "**/*.spec.ts",
  globalSetup: "./tests/e2e/global-setup.ts",
  // One persistent Chromium with the extension loaded; the stub core-api is shared and reset per test.
  workers: 1,
  fullyParallel: false,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [["list"]],
  use: { trace: "retain-on-failure" },
});
