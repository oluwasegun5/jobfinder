import { defineConfig, devices } from "@playwright/test";

// The E2E suite needs the backend stack (`make up`): core-api on :8080 and Mailpit on :8025,
// which is where verification emails land. The web app itself runs here from a production build
// (dev-mode recompiles reload pages mid-test), on a separate port from the compose `web` container.
const port = Number(process.env.E2E_WEB_PORT ?? 3100);

export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/global-setup.ts",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: `http://localhost:${port}`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npm run build && npx next start -p ${port}`,
    url: `http://localhost:${port}`,
    reuseExistingServer: !process.env.CI,
    timeout: 300_000,
    env: { CORE_API_URL: process.env.CORE_API_URL ?? "http://localhost:8080" },
  },
});
