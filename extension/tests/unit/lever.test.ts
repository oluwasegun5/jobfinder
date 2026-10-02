import { beforeEach, describe, expect, it } from "vitest";

import { planFill, type PlanItem } from "../../src/content/plan";
import { filledValue, find, loadFixture, sampleData } from "./helpers";

const DATA = sampleData();
const card = (n: number): string => `textarea[name="cards[8c1f2a60-0000-4000-8000-00000000000${n}][field0]"], input[name="cards[8c1f2a60-0000-4000-8000-00000000000${n}][field0]"], select[name="cards[8c1f2a60-0000-4000-8000-00000000000${n}][field0]"]`;

function skipped(plan: PlanItem[], selector: string): PlanItem | undefined {
  const item = find(plan, selector);
  return item?.action === "skip" ? item : undefined;
}

describe("Lever application form", () => {
  beforeEach(() => loadFixture("lever-form.html"));

  it("fills contact details, links, cover letter, CV and the matching questions", () => {
    const plan = planFill(document, DATA, "lever");

    expect(filledValue(plan, 'input[name="name"]')).toBe("Sam Example");
    expect(filledValue(plan, 'input[name="email"]')).toBe("sam.example@example.test");
    expect(filledValue(plan, 'input[name="phone"]')).toBe("+1 555 0100");
    expect(filledValue(plan, 'input[name="location"]')).toBe("Lagos, Nigeria");
    expect(filledValue(plan, 'input[name="urls[LinkedIn]"]')).toBe("https://www.linkedin.com/in/sam-example");
    expect(filledValue(plan, 'input[name="urls[GitHub]"]')).toBe("https://github.com/sam-example");
    expect(filledValue(plan, 'input[name="urls[Portfolio]"]')).toBe("https://sam-example.example.test");
    expect(filledValue(plan, card(1))).toContain("Backend Engineer role");
    expect(filledValue(plan, card(2))).toBe("I am looking for 90,000 USD a year.");
    expect(filledValue(plan, card(3))).toBe("Four weeks.");
    // Lever hides its native file input with display:none; it is still the resume field.
    expect(find(plan, "#resume-upload-input")).toMatchObject({ action: "fill", key: "cv" });
  });

  it("never fills the EEO block, work authorization, consent or the captcha", () => {
    const plan = planFill(document, DATA, "lever");

    expect(skipped(plan, 'select[name="eeo[gender]"]')).toMatchObject({ skip: "sensitive", category: "demographic" });
    expect(skipped(plan, 'select[name="eeo[race]"]')).toMatchObject({ skip: "sensitive", category: "demographic" });
    expect(skipped(plan, 'select[name="eeo[veteran]"]')).toMatchObject({ skip: "sensitive", category: "demographic" });
    expect(skipped(plan, card(5))).toMatchObject({ skip: "sensitive", category: "work_authorization" });
    expect(skipped(plan, 'input[name="consent[marketing]"]')).toMatchObject({ skip: "sensitive", category: "legal_attestation" });
    expect(skipped(plan, 'input[name="captcha_text"]')).toMatchObject({ skip: "captcha" });
  });

  it("leaves what it cannot match for the user and ignores hidden inputs and the submit button", () => {
    const plan = planFill(document, DATA, "lever");

    expect(skipped(plan, card(4))).toMatchObject({ skip: "no_match" });
    expect(skipped(plan, 'textarea[name="comments"]')).toMatchObject({ skip: "no_match" });
    expect(skipped(plan, 'input[name="urls[Twitter]"]')).toMatchObject({ skip: "no_match" });
    expect(skipped(plan, 'input[name="urls[Other]"]')).toMatchObject({ skip: "no_match" });
    expect(skipped(plan, 'input[name="org"]')).toMatchObject({ skip: "no_match" });
    const elements = new Set(plan.map((i) => i.el));
    document.querySelectorAll('input[type="hidden"], #btn-submit').forEach((el) => expect(elements.has(el as never)).toBe(false));
  });
});
