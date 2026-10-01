import { describe, expect, it } from "vitest";

import { formatAgo, formatCount, formatDuration, formatUntil, formatUsd } from "./format";

const now = new Date("2026-10-01T12:00:00Z");

describe("formatAgo", () => {
  it("speaks in minutes, hours and days", () => {
    expect(formatAgo("2026-10-01T11:59:40Z", now)).toBe("just now");
    expect(formatAgo("2026-10-01T11:55:00Z", now)).toBe("5 min ago");
    expect(formatAgo("2026-10-01T09:00:00Z", now)).toBe("3 h ago");
    expect(formatAgo("2026-09-26T12:00:00Z", now)).toBe("5 d ago");
  });

  it("falls back to the date for old runs and to null for nothing", () => {
    expect(formatAgo("2026-07-01T12:00:00Z", now)).toMatch(/2026/);
    expect(formatAgo(undefined, now)).toBeNull();
    expect(formatAgo("not a date", now)).toBeNull();
  });
});

describe("formatUntil", () => {
  it("says when something is due, and due now once it has passed", () => {
    expect(formatUntil("2026-10-01T12:20:00Z", now)).toBe("in 20 min");
    expect(formatUntil("2026-10-01T15:00:00Z", now)).toBe("in 3 h");
    expect(formatUntil("2026-10-01T11:00:00Z", now)).toBe("due now");
    expect(formatUntil(undefined, now)).toBeNull();
  });
});

describe("formatDuration", () => {
  it("is short for short runs and says running while unfinished", () => {
    expect(formatDuration("2026-10-01T10:00:00.000Z", "2026-10-01T10:00:00.450Z")).toBe("450 ms");
    expect(formatDuration("2026-10-01T10:00:00Z", "2026-10-01T10:00:12Z")).toBe("12 s");
    expect(formatDuration("2026-10-01T10:00:00Z", "2026-10-01T10:03:05Z")).toBe("3 min 05 s");
    expect(formatDuration("2026-10-01T10:00:00Z", undefined)).toBe("running");
  });
});

describe("formatUsd and formatCount", () => {
  it("keeps the precision of a few embedding tokens and of a month of parsing", () => {
    expect(formatUsd(0.000024)).toBe("$0.000024");
    expect(formatUsd(12.5)).toBe("$12.50");
    expect(formatUsd(0.0035)).toBe("$0.0035");
    expect(formatUsd(undefined)).toBe("$0.00");
  });

  it("groups thousands", () => {
    expect(formatCount(1234567)).toBe("1,234,567");
    expect(formatCount(undefined)).toBe("0");
  });
});
