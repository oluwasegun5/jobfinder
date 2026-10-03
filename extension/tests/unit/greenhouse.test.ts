import { beforeEach, describe, expect, it } from "vitest";

import { planFill, skipText, type PlanItem } from "../../src/content/plan";
import { filledValue, find, loadFixture, sampleData } from "./helpers";

const DATA = sampleData();

function skipped(plan: PlanItem[], selector: string): PlanItem | undefined {
  const item = find(plan, selector);
  return item?.action === "skip" ? item : undefined;
}

describe("Greenhouse application form", () => {
  beforeEach(() => loadFixture("greenhouse-form.html"));

  it("fills the contact details, links, cover letter, CV and the matching screening answers", () => {
    const plan = planFill(document, DATA, "greenhouse");

    expect(filledValue(plan, "#first_name")).toBe("Sam");
    expect(filledValue(plan, "#last_name")).toBe("Example");
    expect(filledValue(plan, "#email")).toBe("sam.example@example.test");
    expect(filledValue(plan, "#phone")).toBe("+1 555 0100");
    expect(filledValue(plan, "#job_application_location")).toBe("Lagos, Nigeria");
    expect(filledValue(plan, "#question_1001")).toBe("https://www.linkedin.com/in/sam-example");
    expect(filledValue(plan, "#question_1002")).toBe("https://sam-example.example.test");
    expect(filledValue(plan, "#question_1003")).toBe("https://github.com/sam-example");
    expect(filledValue(plan, "#cover_letter_text")).toContain("Backend Engineer role");
    expect(filledValue(plan, "#question_1004")).toBe("I like building reliable services.");
    expect(filledValue(plan, "#question_1005")).toBe("I am looking for 90,000 USD a year.");
    // The CV input is visually hidden by the site (that is how file inputs are styled), and is still filled.
    expect(find(plan, "#resume")).toMatchObject({ action: "fill", key: "cv" });
  });

  it("never fills demographic, work-authorization or consent fields, and lists them as left for the user", () => {
    const plan = planFill(document, DATA, "greenhouse");

    for (const id of ["#job_application_gender", "#job_application_hispanic_ethnicity", "#job_application_race", "#job_application_veteran_status"]) {
      const item = skipped(plan, id);
      expect(item, id).toMatchObject({ skip: "sensitive", category: "demographic" });
      expect(skipText(item!)).toMatch(/never filled/);
    }
    expect(skipped(plan, "#question_1007")).toMatchObject({ skip: "sensitive", category: "work_authorization" });
    expect(skipped(plan, "#question_1008")).toMatchObject({ skip: "sensitive", category: "work_authorization" });
    expect(skipped(plan, 'input[name="job_application[consent]"]')).toMatchObject({ skip: "sensitive", category: "legal_attestation" });

    // The disability radio group is one entry, labelled by its legend, not one per option.
    const disability = plan.filter((i) => i.label === "Disability Status");
    expect(disability).toHaveLength(1);
    expect(disability[0]).toMatchObject({ action: "skip", skip: "sensitive", category: "demographic" });
  });

  it("does not fill the work-authorization answer even though the pack holds one", () => {
    const plan = planFill(document, DATA, "greenhouse");
    expect(plan.some((i) => i.action === "fill" && i.value === "Yes.")).toBe(false);
  });

  it("leaves hidden fields, honeypots and the submit button out of the plan entirely", () => {
    const plan = planFill(document, DATA, "greenhouse");
    const elements = new Set(plan.map((i) => i.el));
    for (const selector of ['input[type="hidden"]', "#website_url_hp", "#submit_app"]) {
      document.querySelectorAll(selector).forEach((el) => expect(elements.has(el as never), selector).toBe(false));
    }
  });

  it("never fills a captcha", () => {
    const plan = planFill(document, DATA, "greenhouse");
    expect(skipped(plan, "#captcha_answer")).toMatchObject({ skip: "captcha" });
  });

  it("leaves questions it cannot match closely, and attachments, for the user", () => {
    const plan = planFill(document, DATA, "greenhouse");
    expect(skipped(plan, "#question_1006")).toMatchObject({ skip: "no_match" });
    expect(skipped(plan, "#question_1009")).toMatchObject({ skip: "no_match" });
    expect(skipped(plan, "#preferred_name")).toMatchObject({ skip: "no_match" });
    expect(skipped(plan, "#cover_letter")).toMatchObject({ skip: "attachment" });
  });

  it("does not overwrite a value the user typed, and counts it as overwritable", () => {
    (document.querySelector("#first_name") as HTMLInputElement).value = "Typed";
    (document.querySelector("#email") as HTMLInputElement).value = "typed@example.test";

    const plan = planFill(document, DATA, "greenhouse");

    expect(skipped(plan, "#first_name")).toMatchObject({ skip: "has_value" });
    expect(skipped(plan, "#email")).toMatchObject({ skip: "has_value" });
    expect(filledValue(plan, "#last_name")).toBe("Example");
  });

  it("replaces typed values only when asked to overwrite", () => {
    (document.querySelector("#first_name") as HTMLInputElement).value = "Typed";

    const plan = planFill(document, DATA, "greenhouse", { overwrite: true });

    expect(filledValue(plan, "#first_name")).toBe("Sam");
  });

  it("leaves a field alone when the profile has nothing for it", () => {
    const plan = planFill(document, sampleData({ contact: { email: "sam.example@example.test" } }), "greenhouse");

    expect(skipped(plan, "#phone")).toMatchObject({ skip: "no_data" });
    expect(skipped(plan, "#first_name")).toMatchObject({ skip: "no_data" });
    expect(filledValue(plan, "#email")).toBe("sam.example@example.test");
  });

  it("does not fill a CV or a cover letter the pack does not have", () => {
    const plan = planFill(document, sampleData({ cv: null, coverLetter: null }), "greenhouse");

    expect(skipped(plan, "#resume")).toMatchObject({ skip: "no_data" });
    expect(skipped(plan, "#cover_letter_text")).toMatchObject({ skip: "no_data" });
  });

  it("changes nothing in the page while planning", () => {
    const before = document.body.innerHTML;
    planFill(document, DATA, "greenhouse");
    expect(document.body.innerHTML).toBe(before);
  });
});
