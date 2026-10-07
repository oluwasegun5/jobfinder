import { readFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

import { RATE_LIMIT_KEY_PATTERN, rateLimitResetCommand } from "../../e2e/rate-limit-reset";

const ROOT = "/repo";

describe("e2e rate limit reset", () => {
  it("does nothing unless asked for with E2E_RESET_RATE_LIMIT=1", () => {
    expect(rateLimitResetCommand({}, ROOT)).toBeNull();
    expect(rateLimitResetCommand({ E2E_RESET_RATE_LIMIT: "" }, ROOT)).toBeNull();
    expect(rateLimitResetCommand({ E2E_RESET_RATE_LIMIT: "0" }, ROOT)).toBeNull();
    // The old opt-out variable no longer means anything.
    expect(rateLimitResetCommand({ E2E_SKIP_RATE_LIMIT_RESET: "1" }, ROOT)).toBeNull();
    expect(rateLimitResetCommand({ E2E_RESET_RATE_LIMIT: "1" }, ROOT)).not.toBeNull();
  });

  it("names the compose project explicitly, jobfinder by default", () => {
    const byDefault = rateLimitResetCommand({ E2E_RESET_RATE_LIMIT: "1" }, ROOT)!;
    expect(byDefault.command).toBe("docker");
    expect(byDefault.args.slice(0, 3)).toEqual(["compose", "-p", "jobfinder"]);
    const isolated = rateLimitResetCommand(
      { E2E_RESET_RATE_LIMIT: "1", E2E_COMPOSE_PROJECT: "jobfinder-p6-review" },
      ROOT,
    )!;
    expect(isolated.args.slice(0, 3)).toEqual(["compose", "-p", "jobfinder-p6-review"]);
    // An empty value falls back to the default instead of passing an empty project name.
    expect(
      rateLimitResetCommand({ E2E_RESET_RATE_LIMIT: "1", E2E_COMPOSE_PROJECT: "" }, ROOT)!.args.slice(0, 3),
    ).toEqual(["compose", "-p", "jobfinder"]);
  });

  it("uses the prefix core-api really writes its buckets under", () => {
    const limiter = readFileSync(
      path.resolve(
        __dirname,
        "../../../../services/core-api/src/main/java/com/jobfinder/core/identity/internal/RateLimiter.java",
      ),
      "utf8",
    );
    expect(limiter).toContain(`"${RATE_LIMIT_KEY_PATTERN.replace("*", "")}" + bucketName`);
  });

  it("deletes only rate-limit keys and never flushes the database", () => {
    const { args } = rateLimitResetCommand({ E2E_RESET_RATE_LIMIT: "1" }, ROOT)!;
    expect(args.join(" ")).not.toMatch(/flush/i);
    expect(RATE_LIMIT_KEY_PATTERN).toBe("rl:*");
    expect(args.slice(-4)[0]).toBe("EVAL");
    expect(args.slice(-3)[0]).toContain("'DEL'");
    expect(args.slice(-1)[0]).toBe("rl:*");
    expect(args).toContain("-T");
    expect(args).toContain("--env-file");
    expect(args).toContain("/repo/infra/docker-compose.yml");
  });
});
