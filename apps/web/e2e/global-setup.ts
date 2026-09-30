import { execFileSync } from "node:child_process";
import path from "node:path";

/**
 * core-api rate-limits signup and login per IP (docs/adr/0012), and every E2E run signs up fresh
 * users from the same address. Redis holds nothing but those counters locally, so clear it first.
 * Set E2E_SKIP_RATE_LIMIT_RESET=1 when running against a stack whose Redis you do not own.
 */
export default function globalSetup() {
  if (process.env.E2E_SKIP_RATE_LIMIT_RESET) return;
  const root = path.resolve(__dirname, "../../..");
  try {
    execFileSync("docker", ["compose", "-f", path.join(root, "infra/docker-compose.yml"), "--env-file", path.join(root, ".env"), "exec", "-T", "redis", "redis-cli", "FLUSHDB"], {
      stdio: "pipe",
    });
  } catch (error) {
    console.warn(`Could not reset rate limits in Redis (${(error as Error).message}); signups may be throttled.`);
  }
}
