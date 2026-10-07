import { execFileSync } from "node:child_process";
import path from "node:path";

import { rateLimitResetCommand } from "./rate-limit-reset";

/**
 * core-api rate-limits signup and login per IP (docs/adr/0012), and every E2E run signs up fresh users from the same
 * address. Opt in with E2E_RESET_RATE_LIMIT=1 to delete the rate-limit keys (rl:*) of the compose stack the run talks
 * to; nothing else in Redis is touched. The stack is named explicitly with E2E_COMPOSE_PROJECT (default "jobfinder"),
 * so set it when the backend is an isolated stack (see e2e/README.md).
 */
export default function globalSetup() {
  const reset = rateLimitResetCommand(process.env, path.resolve(__dirname, "../../.."));
  if (!reset) return;
  try {
    execFileSync(reset.command, reset.args, { stdio: "pipe" });
  } catch (error) {
    console.warn(`Could not reset rate limits in Redis (${(error as Error).message}); signups may be throttled.`);
  }
}
