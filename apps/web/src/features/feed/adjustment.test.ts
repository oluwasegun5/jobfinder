import { describe, expect, it } from "vitest";

import { describeAdjustment, formatPoints } from "./adjustment";

describe("describeAdjustment", () => {
  it("says nothing when nothing moved the job", () => {
    expect(describeAdjustment(0, [])).toBeUndefined();
    expect(describeAdjustment(undefined, undefined)).toBeUndefined();
    expect(describeAdjustment(0.01, [])).toBeUndefined();
  });

  it("words a demotion and a boost, naming the job behind it", () => {
    expect(describeAdjustment(-15, [{ code: "HIDDEN_SIMILAR_TITLE", points: -15, count: 1, example: "Java Engineer" }])).toBe(
      "Ranked lower by 15 points: you hid a similar job (“Java Engineer”).",
    );
    expect(describeAdjustment(5, [{ code: "APPLIED_SIMILAR_TITLE", points: 5, count: 1, example: "Data Analyst" }])).toBe(
      "Ranked higher by 5 points: you applied to a similar job (“Data Analyst”).",
    );
  });

  it("joins several causes, ignores the cap markers and uses the singular for one point", () => {
    expect(
      describeAdjustment(-30, [
        { code: "HIDDEN_SAME_COMPANY", points: -30, count: 4 },
        { code: "PENALTY_CAPPED", points: 0, count: 0 },
      ]),
    ).toBe("Ranked lower by 30 points: you hid another job at this company.");
    expect(describeAdjustment(1, [{ code: "SAVED_SAME_COMPANY", points: 1, count: 1 }])).toBe(
      "Ranked higher by 1 point: you saved another job at this company.",
    );
  });

  it("falls back to the bare amount for a code it does not know", () => {
    expect(describeAdjustment(-2, [{ code: "SOMETHING_NEW" as never, points: -2, count: 1 }])).toBe("Ranked lower by 2 points.");
  });
});

describe("formatPoints", () => {
  it("drops a trailing .0 and keeps one decimal otherwise", () => {
    expect(formatPoints(-15)).toBe("15");
    expect(formatPoints(6.5)).toBe("6.5");
    expect(formatPoints(-6.04)).toBe("6");
  });
});
