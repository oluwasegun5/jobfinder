import path from "node:path";

/** Every core-api rate-limit bucket lives under this prefix (RateLimiter.java: "rl:" + bucket + ":" + subject). */
export const RATE_LIMIT_KEY_PATTERN = "rl:*";

/** Deletes the keys matching ARGV[1] and nothing else: no FLUSHDB, so anything else in that Redis survives. */
const DELETE_MATCHING_KEYS = "for _, k in ipairs(redis.call('KEYS', ARGV[1])) do redis.call('DEL', k) end return 0";

/**
 * The command that clears the rate-limit buckets of the compose stack the E2E run talks to, or null when the run did
 * not ask for it. Opt-in: E2E_RESET_RATE_LIMIT=1. The compose project is always passed explicitly (E2E_COMPOSE_PROJECT,
 * default "jobfinder"), because every worktree's compose file says `name: jobfinder` and a bare `docker compose` would
 * otherwise reach the main development stack's Redis.
 */
export function rateLimitResetCommand(
  env: Record<string, string | undefined>,
  root: string,
): { command: string; args: string[] } | null {
  if (env.E2E_RESET_RATE_LIMIT !== "1") return null;
  const project = env.E2E_COMPOSE_PROJECT || "jobfinder";
  return {
    command: "docker",
    args: [
      "compose",
      "-p",
      project,
      "-f",
      path.join(root, "infra/docker-compose.yml"),
      "--env-file",
      path.join(root, ".env"),
      "exec",
      "-T",
      "redis",
      "redis-cli",
      "EVAL",
      DELETE_MATCHING_KEYS,
      "0",
      RATE_LIMIT_KEY_PATTERN,
    ],
  };
}
