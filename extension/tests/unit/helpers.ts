import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import type { FillData } from "../../src/shared/types";
import type { PlanItem } from "../../src/content/plan";

export const JOB_ID = "5b9a3c10-6f1e-4a52-9c71-0d2f3a4b5c6d";
export const PACK_ID = "7c1d4e20-8a2f-4b63-8d82-1e3a4b5c6d7e";

/** Synthetic candidate and pack: no real person or company. */
export function sampleData(overrides: Partial<FillData> = {}): FillData {
  return {
    job: { id: JOB_ID, title: "Backend Engineer", company: "Example Corp" },
    applicationId: null,
    applicationStatus: null,
    packId: PACK_ID,
    contact: {
      fullName: "Sam Example",
      firstName: "Sam",
      lastName: "Example",
      email: "sam.example@example.test",
      phone: "+1 555 0100",
      location: "Lagos, Nigeria",
      linkedin: "https://www.linkedin.com/in/sam-example",
      github: "https://github.com/sam-example",
      portfolio: "https://sam-example.example.test",
    },
    coverLetter: "Dear Hiring Team,\n\nI would like to apply for the Backend Engineer role.\n\nSam Example",
    screening: [
      { id: "WHY_COMPANY_ROLE", question: "Why do you want to work at this company and in this role?", answer: "I like building reliable services." },
      { id: "SALARY_EXPECTATION", question: "What are your salary expectations?", answer: "I am looking for 90,000 USD a year." },
      { id: "NOTICE_PERIOD", question: "What is your notice period or earliest start date?", answer: "Four weeks." },
      { id: "WORK_AUTHORIZATION", question: "Are you authorized to work in this country, and do you need sponsorship?", answer: "Yes." },
    ],
    cv: { kind: "resume", resumeId: "9e3f6a40-0c4b-4d85-8fa4-3a5c6d7e8f90" },
    used: { resumeDocumentId: undefined, coverLetterDocumentId: "a1b2c3d4-0000-4000-8000-000000000001" },
    ...overrides,
  };
}

/** Puts a fixture's <body> in the document (its inline script is not run). */
export function loadFixture(name: string): void {
  const html = readFileSync(resolve(import.meta.dirname, "../fixtures", name), "utf8");
  const parsed = new DOMParser().parseFromString(html, "text/html");
  parsed.querySelectorAll("script").forEach((s) => s.remove());
  document.body.innerHTML = parsed.body.innerHTML;
}

export function find(plan: PlanItem[], selector: string): PlanItem | undefined {
  const el = document.querySelector(selector);
  return plan.find((i) => i.el === el);
}

export function filledValue(plan: PlanItem[], selector: string): string | undefined {
  const item = find(plan, selector);
  return item?.action === "fill" ? (item.value ?? "<file>") : undefined;
}
