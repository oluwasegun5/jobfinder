import { describe, expect, it } from "vitest";

import { ALLOWED, detectAts } from "../../src/content/ats";

describe("detectAts", () => {
  it.each([
    ["boards.greenhouse.io", "greenhouse"],
    ["job-boards.greenhouse.io", "greenhouse"],
    ["job-boards.eu.greenhouse.io", "greenhouse"],
    ["jobs.lever.co", "lever"],
    ["jobs.eu.lever.co", "lever"],
    ["jobs.ashbyhq.com", "ashby"],
    ["acme.wd5.myworkdayjobs.com", "workday"],
    ["JOBS.LEVER.CO", "lever"],
  ])("%s is %s", (host, ats) => {
    expect(detectAts(host)).toBe(ats);
  });

  it.each([
    "example.com",
    "greenhouse.io",
    "boards.greenhouse.io.evil.example",
    "evil-jobs.lever.co.example",
    "myworkdayjobs.com",
    "notmyworkdayjobs.com",
    "jobs.ashbyhq.com.evil.example",
  ])("%s is not an ATS we read", (host) => {
    expect(detectAts(host)).toBeNull();
  });
});

describe("what is filled where", () => {
  it("Greenhouse and Lever are filled end to end", () => {
    for (const ats of ["greenhouse", "lever"] as const) {
      for (const key of ["email", "cv", "coverLetter", "screening", "linkedin"] as const) {
        expect(ALLOWED[ats].has(key)).toBe(true);
      }
    }
  });

  it("Ashby gets contact details and links, Workday the contact details of the step on screen", () => {
    expect(ALLOWED.ashby.has("email")).toBe(true);
    expect(ALLOWED.ashby.has("cv")).toBe(false);
    expect(ALLOWED.ashby.has("screening")).toBe(false);
    expect([...ALLOWED.workday].sort()).toEqual(["city", "email", "firstName", "lastName", "phone"]);
  });
});
