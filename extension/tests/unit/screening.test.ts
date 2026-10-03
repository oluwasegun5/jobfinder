import { describe, expect, it } from "vitest";

import { matchScreening, similarity } from "../../src/content/screening";
import { sampleData } from "./helpers";

const { screening } = sampleData();

describe("screening answers fill a question only when it matches closely", () => {
  it("matches the same question, ignoring case, punctuation and required markers", () => {
    expect(matchScreening("What are your salary expectations? *", screening)?.id).toBe("SALARY_EXPECTATION");
    expect(matchScreening("WHY DO YOU WANT TO WORK AT THIS COMPANY AND IN THIS ROLE", screening)?.id).toBe("WHY_COMPANY_ROLE");
  });

  it("matches the common phrasings of the questions in the catalogue", () => {
    expect(matchScreening("Why do you want to work at Example Corp?", screening)?.id).toBe("WHY_COMPANY_ROLE");
    expect(matchScreening("Why are you interested in this role?", screening)?.id).toBe("WHY_COMPANY_ROLE");
    expect(matchScreening("What is your notice period?", screening)?.id).toBe("NOTICE_PERIOD");
    expect(matchScreening("Expected salary", screening)?.id).toBe("SALARY_EXPECTATION");
  });

  it("does not match a related but different question", () => {
    expect(matchScreening("Why are you leaving your current role?", screening)).toBeNull();
    expect(matchScreening("What is your current salary?", screening)).toBeNull();
    expect(matchScreening("Describe a time you disagreed with a teammate.", screening)).toBeNull();
    expect(matchScreening("How did you hear about this job?", screening)).toBeNull();
    expect(matchScreening("", screening)).toBeNull();
  });

  it("never uses the work-authorization answer, even for its own question text", () => {
    expect(matchScreening("Are you authorized to work in this country, and do you need sponsorship?", screening)).toBeNull();
  });

  it("does not guess when two answers could fit", () => {
    const twice = [
      { id: "STRENGTHS", question: "What are your key strengths?", answer: "A" },
      { id: "STRENGTHS2", question: "What are your key strengths?", answer: "B" },
    ];
    expect(matchScreening("What are your key strengths?", twice)).toBeNull();
  });

  it("ignores an answer with no text", () => {
    expect(matchScreening("What are your salary expectations?", [{ id: "SALARY_EXPECTATION", question: "What are your salary expectations?", answer: "  " }])).toBeNull();
  });

  it("refuses an absurdly long label", () => {
    expect(matchScreening(`What are your salary expectations? ${"x ".repeat(200)}`, screening)).toBeNull();
  });

  it("measures similarity on content words only", () => {
    expect(similarity("What is your notice period or earliest start date?", "What is your earliest start date or notice period")).toBe(1);
    expect(similarity("a b", "a b")).toBe(0);
  });
});
