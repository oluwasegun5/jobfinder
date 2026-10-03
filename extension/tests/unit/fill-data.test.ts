import { describe, expect, it } from "vitest";

import { approvedParts, chooseCv, contactFrom, coverLetterText, linksFrom, screeningFrom, splitName } from "../../src/background/fill-data";

const U1 = "11111111-1111-4111-8111-111111111111";
const U2 = "22222222-2222-4222-8222-222222222222";
const U3 = "33333333-3333-4333-8333-333333333333";

describe("splitName", () => {
  it("takes the first word as the first name and the rest as the last name", () => {
    expect(splitName("Sam Example")).toEqual({ firstName: "Sam", lastName: "Example" });
    expect(splitName("  Sam  van der Example ")).toEqual({ firstName: "Sam", lastName: "van der Example" });
  });
  it("leaves the last name empty for a single word and everything empty for nothing", () => {
    expect(splitName("Sam")).toEqual({ firstName: "Sam", lastName: undefined });
    expect(splitName("   ")).toEqual({});
  });
});

describe("linksFrom", () => {
  it("picks LinkedIn, GitHub and one portfolio link, ignoring anything that is not http(s)", () => {
    expect(
      linksFrom([
        { label: "me", url: "https://www.linkedin.com/in/sam-example" },
        { label: "code", url: "https://github.com/sam-example" },
        { label: "Portfolio", url: "https://sam.example.test" },
        { label: "other", url: "https://second.example.test" },
        { url: "javascript:alert(1)" },
        { url: "not a url" },
      ]),
    ).toEqual({ linkedin: "https://www.linkedin.com/in/sam-example", github: "https://github.com/sam-example", portfolio: "https://sam.example.test/" });
  });
  it("copes with no links", () => {
    expect(linksFrom(undefined)).toEqual({});
  });
});

describe("contactFrom", () => {
  it("combines the profile, the account email and the links, trimming blanks to undefined", () => {
    expect(contactFrom({ fullName: " Sam Example ", phone: " ", location: "Lagos, Nigeria" }, "sam@example.test")).toMatchObject({
      fullName: "Sam Example",
      firstName: "Sam",
      lastName: "Example",
      email: "sam@example.test",
      phone: undefined,
      location: "Lagos, Nigeria",
    });
  });
});

describe("cover letter and answers from an approved document", () => {
  it("joins salutation, paragraphs, closing and signature", () => {
    expect(coverLetterText({ salutation: "Dear team,", paragraphs: ["One.", " Two. "], closing: "Regards,", signature: "Sam" })).toBe("Dear team,\n\nOne.\n\nTwo.\n\nRegards,\n\nSam");
    expect(coverLetterText({ salutation: "Hi" })).toBeNull();
    expect(coverLetterText(undefined)).toBeNull();
  });

  it("drops answers that need the user's input and malformed ones", () => {
    expect(
      screeningFrom({
        answers: [
          { id: "STRENGTHS", question: "Q1", answer: " A1 ", status: "GENERATED" },
          { id: "NOTICE_PERIOD", question: "Q2", answer: "", status: "NEEDS_INPUT" },
          { id: "SALARY_EXPECTATION", question: "Q3", answer: "typed", status: "NEEDS_INPUT" },
          { id: "X", question: 1, answer: "a" },
          "junk",
        ],
      }),
    ).toEqual([{ id: "STRENGTHS", question: "Q1", answer: "A1" }]);
    expect(screeningFrom(undefined)).toEqual([]);
  });
});

describe("approvedParts", () => {
  const part = (type: string, status: string, state = "READY", id = U1, content: Record<string, unknown> = {}) => ({ type, state, document: { id, type, status, content } });

  it("uses approved documents only: a draft is the user's work in progress", () => {
    const parts = approvedParts({
      parts: [
        part("COVER_LETTER", "DRAFT", "READY", U1, { paragraphs: ["Hello."] }),
        part("SCREENING_ANSWERS", "APPROVED", "READY", U2, { answers: [{ id: "STRENGTHS", question: "Q", answer: "A", status: "GENERATED" }] }),
        part("TAILORED_RESUME", "APPROVED", "READY", U3),
      ],
    } as never);
    expect(parts.coverLetter).toBeNull();
    expect(parts.screening).toHaveLength(1);
    expect(parts.resumeDocumentId).toBe(U3);
    expect(parts.used).toEqual({ screeningAnswersDocumentId: U2 });
  });

  it("ignores parts that are not ready or have a malformed id", () => {
    const parts = approvedParts({ parts: [part("COVER_LETTER", "APPROVED", "FAILED", U1, { paragraphs: ["x"] }), part("TAILORED_RESUME", "APPROVED", "READY", "not-a-uuid")] } as never);
    expect(parts.coverLetter).toBeNull();
    expect(parts.resumeDocumentId).toBeNull();
  });

  it("copes with no pack", () => {
    expect(approvedParts(undefined)).toEqual({ coverLetter: null, screening: [], used: {}, resumeDocumentId: null });
  });
});

describe("chooseCv", () => {
  const file = (id: string, template: string, createdAt: string) => ({ id, template, createdAt }) as never;
  const resume = (id: string, primary: boolean) => ({ id, primary }) as never;

  it("prefers the ATS-template file of the approved tailored resume, then the newest", () => {
    expect(chooseCv(U1, [file(U2, "STYLED", "2026-02-02"), file(U3, "ATS", "2026-01-01")], [resume(U1, true)])).toEqual({ kind: "document", documentId: U1, fileId: U3 });
    expect(chooseCv(U1, [file(U2, "ATS", "2026-01-01"), file(U3, "ATS", "2026-03-01")], [])).toEqual({ kind: "document", documentId: U1, fileId: U3 });
  });

  it("falls back to the primary resume when nothing was rendered, and to nothing without one", () => {
    expect(chooseCv(U1, [], [resume(U2, false), resume(U3, true)])).toEqual({ kind: "resume", resumeId: U3 });
    expect(chooseCv(null, [], [resume(U2, true)])).toEqual({ kind: "resume", resumeId: U2 });
    expect(chooseCv(null, [], [resume(U2, false)])).toBeNull();
  });
});
