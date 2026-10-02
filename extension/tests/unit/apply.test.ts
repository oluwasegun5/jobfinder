import { beforeEach, describe, expect, it, vi } from "vitest";

import { FILLED_ATTRIBUTE, FillSession } from "../../src/content/apply";
import { planFill, usedFrom } from "../../src/content/plan";
import { find, loadFixture, sampleData } from "./helpers";

const DATA = sampleData();
const input = (selector: string): HTMLInputElement => document.querySelector(selector) as HTMLInputElement;

describe("filling, highlighting and undoing", () => {
  beforeEach(() => loadFixture("greenhouse-form.html"));

  it("writes the values, fires input and change, and outlines what it filled", () => {
    const events: string[] = [];
    input("#first_name").addEventListener("input", () => events.push("input"));
    input("#first_name").addEventListener("change", () => events.push("change"));
    const session = new FillSession();

    const filled = session.apply(planFill(document, DATA, "greenhouse"), null);

    expect(input("#first_name").value).toBe("Sam");
    expect(input("#email").value).toBe("sam.example@example.test");
    expect((document.querySelector("#cover_letter_text") as HTMLTextAreaElement).value).toContain("Backend Engineer");
    expect(events).toEqual(["input", "change"]);
    expect(input("#first_name").getAttribute(FILLED_ATTRIBUTE)).toBe("firstName");
    expect(input("#first_name").style.outline).toContain("solid");
    // The CV could not be attached (no file given), so it is not reported as filled.
    expect(filled.some((i) => i.key === "cv")).toBe(false);
    expect(input("#resume").hasAttribute(FILLED_ATTRIBUTE)).toBe(false);
  });

  it("leaves skipped fields untouched and unmarked", () => {
    const session = new FillSession();
    session.apply(planFill(document, DATA, "greenhouse"), null);

    for (const selector of ["#job_application_gender", "#question_1007", "#preferred_name", "#question_1006", "#captcha_answer"]) {
      const el = document.querySelector(selector) as HTMLInputElement;
      expect(el.value, selector).toBe("");
      expect(el.hasAttribute(FILLED_ATTRIBUTE), selector).toBe(false);
      expect(el.style.outline, selector).toBe("");
    }
    expect(document.querySelectorAll('input[name="job_application[disability_status]"]:checked')).toHaveLength(0);
    expect(document.querySelector<HTMLInputElement>('input[name="job_application[consent]"]')!.checked).toBe(false);
  });

  it("keeps what the user typed unless Overwrite is used, and undo restores it either way", () => {
    input("#first_name").value = "Typed";
    const session = new FillSession();

    session.apply(planFill(document, DATA, "greenhouse"), null);
    expect(input("#first_name").value).toBe("Typed");

    session.apply(planFill(document, DATA, "greenhouse", { overwrite: true }), null);
    expect(input("#first_name").value).toBe("Sam");

    session.undo();
    expect(input("#first_name").value).toBe("Typed");
    expect(input("#last_name").value).toBe("");
    expect(input("#first_name").hasAttribute(FILLED_ATTRIBUTE)).toBe(false);
    expect(input("#first_name").style.outline).toBe("");
    expect(session.count).toBe(0);
  });

  it("restores a pre-existing inline outline when undoing", () => {
    input("#email").style.outline = "1px dotted red";
    const session = new FillSession();
    session.apply(planFill(document, DATA, "greenhouse"), null);
    expect(input("#email").style.outline).toContain("solid");

    session.undo();

    expect(input("#email").style.outline).toBe("1px dotted red");
  });

  it("never submits: no submit event, no click on the submit button", () => {
    const form = document.querySelector("form") as HTMLFormElement;
    const submit = vi.fn();
    const clicked = vi.fn();
    form.addEventListener("submit", submit);
    input("#submit_app").addEventListener("click", clicked);
    const keys = vi.fn();
    document.addEventListener("keydown", keys, true);
    document.addEventListener("keypress", keys, true);

    const session = new FillSession();
    session.apply(planFill(document, DATA, "greenhouse"), null);
    session.apply(planFill(document, DATA, "greenhouse", { overwrite: true }), null);
    session.undo();

    expect(submit).not.toHaveBeenCalled();
    expect(clicked).not.toHaveBeenCalled();
    expect(keys).not.toHaveBeenCalled();
  });

  it("does not touch the plan's skipped items even when handed a mixed plan", () => {
    const plan = planFill(document, DATA, "greenhouse");
    const gender = find(plan, "#job_application_gender")!;
    expect(gender.action).toBe("skip");
    const session = new FillSession();

    const filled = session.apply(plan, null);

    expect(filled).not.toContain(gender);
  });
});

describe("which documents count as used", () => {
  it("records only the documents whose content went into the form", () => {
    const offered = { resumeDocumentId: "r", coverLetterDocumentId: "c", screeningAnswersDocumentId: "s" };
    const item = (key: string) => ({ key }) as never;

    expect(usedFrom(offered, [item("firstName")])).toEqual({});
    expect(usedFrom(offered, [item("cv"), item("coverLetter")])).toEqual({ resumeDocumentId: "r", coverLetterDocumentId: "c" });
    expect(usedFrom(offered, [item("screening")])).toEqual({ screeningAnswersDocumentId: "s" });
    expect(usedFrom({}, [item("cv")])).toEqual({});
  });
});
