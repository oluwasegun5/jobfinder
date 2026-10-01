import { describe, expect, it } from "vitest";

import { formatPosted, formatSalary, safeHref } from "./format";

describe("formatSalary", () => {
  it("shows ranges, open ends and a single figure with the period", () => {
    expect(formatSalary({ min: 50000, max: 80000, currency: "USD", period: "YEAR" })).toBe("$50,000 - $80,000 / year");
    expect(formatSalary({ min: 45, currency: "GBP", period: "HOUR" })).toBe("from £45 / hour");
    expect(formatSalary({ max: 6000, currency: "EUR", period: "MONTH" })).toBe("up to €6,000 / month");
    expect(formatSalary({ min: 70000, max: 70000, currency: "USD" })).toBe("$70,000");
  });

  it("is null when no salary was stated", () => {
    expect(formatSalary(undefined)).toBeNull();
    expect(formatSalary({ currency: "USD" })).toBeNull();
  });
});

describe("formatPosted", () => {
  const now = new Date("2026-10-01T12:00:00Z");
  it("uses relative wording for recent posts", () => {
    expect(formatPosted("2026-10-01T01:00:00Z", now)).toBe("today");
    expect(formatPosted("2026-09-30T01:00:00Z", now)).toBe("yesterday");
    expect(formatPosted("2026-09-26T12:00:00Z", now)).toBe("5 days ago");
    expect(formatPosted("2026-09-10T12:00:00Z", now)).toBe("3 weeks ago");
  });
  it("is null for missing or invalid dates", () => {
    expect(formatPosted(undefined, now)).toBeNull();
    expect(formatPosted("not a date", now)).toBeNull();
  });
});

describe("safeHref", () => {
  it("allows only http(s) links", () => {
    expect(safeHref("https://example.com/a")).toBe("https://example.com/a");
    expect(safeHref("javascript:alert(1)")).toBeUndefined();
    expect(safeHref("data:text/html,x")).toBeUndefined();
    expect(safeHref("not a url")).toBeUndefined();
    expect(safeHref(undefined)).toBeUndefined();
  });
});
