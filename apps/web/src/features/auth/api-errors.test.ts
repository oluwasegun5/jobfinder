import { describe, expect, it } from "vitest";

import { problemFieldErrors, problemMessage } from "./api-errors";

describe("problemFieldErrors", () => {
  it("words each server validation failure for a person", () => {
    expect(
      problemFieldErrors({
        errors: [
          { field: "experience[0].company", message: "must not be blank" },
          { field: "yearsExperience", message: "must be less than or equal to 80" },
          { field: "education[1].start_date", message: "must be YYYY or YYYY-MM" },
        ],
      }),
    ).toEqual([
      "Experience 1 → company: must not be blank",
      "YearsExperience: must be less than or equal to 80",
      "Education 2 → start date: must be YYYY or YYYY-MM",
    ]);
  });

  it("ignores anything that is not a list of field errors", () => {
    expect(problemFieldErrors(undefined)).toEqual([]);
    expect(problemFieldErrors({ errors: "nope" })).toEqual([]);
    expect(problemFieldErrors({ errors: [{ field: 3 }, null] })).toEqual([]);
  });
});

describe("problemMessage", () => {
  it("prefers the server's detail and falls back otherwise", () => {
    expect(problemMessage({ detail: "Nope." }, "fallback")).toBe("Nope.");
    expect(problemMessage({}, "fallback")).toBe("fallback");
  });
});
