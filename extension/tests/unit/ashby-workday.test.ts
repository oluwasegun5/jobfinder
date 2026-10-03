import { describe, expect, it } from "vitest";

import { planFill, type PlanItem } from "../../src/content/plan";
import { filledValue, find, loadFixture, sampleData } from "./helpers";

const DATA = sampleData();

function skipped(plan: PlanItem[], selector: string): PlanItem | undefined {
  const item = find(plan, selector);
  return item?.action === "skip" ? item : undefined;
}

describe("Ashby application form (contact details and links only)", () => {
  it("fills the contact fields and the LinkedIn link, and nothing else", () => {
    loadFixture("ashby-form.html");
    const plan = planFill(document, DATA, "ashby");

    expect(filledValue(plan, "#_systemfield_name")).toBe("Sam Example");
    expect(filledValue(plan, "#_systemfield_email")).toBe("sam.example@example.test");
    expect(filledValue(plan, "#f-phone")).toBe("+1 555 0100");
    expect(filledValue(plan, "#f-location")).toBe("Lagos, Nigeria");
    expect(filledValue(plan, "#f-linkedin")).toBe("https://www.linkedin.com/in/sam-example");

    expect(skipped(plan, "#_systemfield_resume")).toMatchObject({ skip: "not_on_this_site" });
    expect(skipped(plan, "#f-why")).toMatchObject({ skip: "not_on_this_site" });
    expect(plan.filter((i) => i.action === "fill")).toHaveLength(5);
  });

  it("never selects the work-authorization or gender radio groups", () => {
    loadFixture("ashby-form.html");
    const plan = planFill(document, DATA, "ashby");

    expect(plan.find((i) => i.label.startsWith("Are you legally authorized"))).toMatchObject({ action: "skip", skip: "sensitive" });
    expect(plan.find((i) => i.label === "Gender")).toMatchObject({ action: "skip", skip: "sensitive", category: "demographic" });
    expect(plan.filter((i) => i.label === "Gender")).toHaveLength(1);
  });
});

describe("Workday 'My Information' step (contact details of the visible step only)", () => {
  it("fills the given and family name, city, email and phone", () => {
    loadFixture("workday-my-information.html");
    const plan = planFill(document, DATA, "workday");

    expect(filledValue(plan, '[data-automation-id="legalNameSection_firstName"]')).toBe("Sam");
    expect(filledValue(plan, '[data-automation-id="legalNameSection_lastName"]')).toBe("Example");
    expect(filledValue(plan, '[data-automation-id="addressSection_city"]')).toBe("Lagos");
    expect(filledValue(plan, '[data-automation-id="email"]')).toBe("sam.example@example.test");
    expect(filledValue(plan, '[data-automation-id="phone-number"]')).toBe("+1 555 0100");
    expect(plan.filter((i) => i.action === "fill")).toHaveLength(5);
  });

  it("leaves the address, the extension, the source question and the gender field for the user", () => {
    loadFixture("workday-my-information.html");
    const plan = planFill(document, DATA, "workday");

    expect(skipped(plan, '[data-automation-id="addressSection_addressLine1"]')).toMatchObject({ skip: "not_on_this_site" });
    expect(skipped(plan, '[data-automation-id="addressSection_postalCode"]')).toMatchObject({ skip: "not_on_this_site" });
    expect(skipped(plan, '[data-automation-id="phone-extension"]')).toMatchObject({ skip: "not_on_this_site" });
    expect(skipped(plan, '[data-automation-id="source"]')).toMatchObject({ skip: "not_on_this_site" });
    expect(skipped(plan, '[data-automation-id="gender"]')).toMatchObject({ skip: "sensitive", category: "demographic" });
  });
});
