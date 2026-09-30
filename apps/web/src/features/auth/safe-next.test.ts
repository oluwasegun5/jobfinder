import { describe, expect, it } from "vitest";

import { safeNextPath } from "./safe-next";

describe("safeNextPath", () => {
  it("keeps same-origin paths", () => {
    expect(safeNextPath("/dashboard?tab=1")).toBe("/dashboard?tab=1");
  });

  it.each([null, "", "https://evil.test", "//evil.test", "/\\evil.test", "dashboard"])(
    "falls back for %s",
    (value) => {
      expect(safeNextPath(value)).toBe("/dashboard");
    },
  );
});
