import { beforeEach, describe, expect, it } from "vitest";

import { classify } from "../../src/content/classify";
import { isHidden, labelOf, normalise, type FormControl } from "../../src/content/dom";
import { planFill } from "../../src/content/plan";
import { sampleData } from "./helpers";

function mount(html: string): void {
  document.body.innerHTML = html;
}
const control = (selector: string): FormControl => document.querySelector(selector) as FormControl;

describe("classification", () => {
  beforeEach(() => mount(""));

  it("never fills a password field, whatever it is called", () => {
    mount('<label for="p">Email</label><input id="p" name="email" type="password">');
    expect(classify(control("#p"))).toEqual({ kind: "never", reason: "password" });
  });

  it("treats captcha widgets by class, id or name as never filled", () => {
    mount('<div class="g-recaptcha"><input id="a" type="text" aria-label="Answer"></div><input id="b" name="captcha_response" type="text">');
    expect(classify(control("#a"))).toEqual({ kind: "never", reason: "captcha" });
    expect(classify(control("#b"))).toEqual({ kind: "never", reason: "captcha" });
  });

  it("recognises sensitive questions from the label, the name, the legend or an aria label", () => {
    mount(`
      <label for="a">Pronouns</label><input id="a" type="text">
      <input id="b" type="text" name="job_application[veteran_status]">
      <fieldset><legend>Voluntary self-identification</legend><label for="c">Answer</label><input id="c" type="text"></fieldset>
      <input id="d" type="text" aria-label="Do you now or will you require visa sponsorship?">
      <label for="e">Social Security Number</label><input id="e" type="text">
      <label for="f">I certify that the information above is true</label><input id="f" type="text">
      <label for="g">Date of birth</label><input id="g" type="text">
    `);
    expect(classify(control("#a"))).toMatchObject({ kind: "sensitive", category: "demographic" });
    expect(classify(control("#b"))).toMatchObject({ kind: "sensitive", category: "demographic" });
    expect(classify(control("#c"))).toMatchObject({ kind: "sensitive", category: "demographic" });
    expect(classify(control("#d"))).toMatchObject({ kind: "sensitive", category: "work_authorization" });
    expect(classify(control("#e"))).toMatchObject({ kind: "sensitive", category: "government_id" });
    expect(classify(control("#f"))).toMatchObject({ kind: "sensitive", category: "legal_attestation" });
    expect(classify(control("#g"))).toMatchObject({ kind: "sensitive", category: "demographic" });
  });

  it("does not mistake ordinary words for sensitive ones", () => {
    mount('<label for="a">Message to the hiring manager</label><textarea id="a"></textarea><label for="b">Language</label><input id="b" type="text">');
    expect(classify(control("#a")).kind).not.toBe("sensitive");
    expect(classify(control("#b")).kind).not.toBe("sensitive");
  });

  it("only treats a field as the candidate's own detail when it is not about someone else", () => {
    mount('<label for="a">Referrer email</label><input id="a" type="email" name="ref_email"><label for="b">Manager name</label><input id="b" type="text"><label for="c">Company website</label><input id="c" type="text">');
    expect(classify(control("#a")).kind).not.toBe("data");
    expect(classify(control("#b")).kind).not.toBe("data");
    expect(classify(control("#c")).kind).not.toBe("data");
  });

  it("uses autocomplete tokens", () => {
    mount('<input id="a" type="text" autocomplete="given-name"><input id="b" type="text" autocomplete="family-name"><input id="c" type="text" autocomplete="tel-national">');
    expect(classify(control("#a"))).toEqual({ kind: "data", key: "firstName" });
    expect(classify(control("#b"))).toEqual({ kind: "data", key: "lastName" });
    expect(classify(control("#c"))).toEqual({ kind: "data", key: "phone" });
  });

  it("does not take a phone type or country code for a phone number", () => {
    mount('<label for="a">Phone Extension</label><input id="a" type="text"><label for="b">Phone type</label><input id="b" type="text"><label for="c">Country phone code</label><input id="c" type="text">');
    for (const id of ["#a", "#b", "#c"]) expect(classify(control(id)).kind).not.toBe("data");
  });

  it("never selects checkboxes and radios, and leaves dropdowns alone", () => {
    mount('<label><input id="a" type="checkbox"> Subscribe to our newsletter</label><select id="b" aria-label="Preferred contact"><option>x</option></select>');
    expect(classify(control("#a"))).toEqual({ kind: "never", reason: "choice" });
    expect(classify(control("#b"))).toEqual({ kind: "unknown", reason: "dropdown" });
  });

  it("only takes a file input for a resume, never for a cover letter file or anything else", () => {
    mount('<label for="a">Resume/CV</label><input id="a" type="file"><label for="b">Cover Letter</label><input id="b" type="file"><label for="c">Portfolio PDF</label><input id="c" type="file"><label for="d">Curriculum Vitae</label><input id="d" type="file">');
    expect(classify(control("#a"))).toEqual({ kind: "data", key: "cv" });
    expect(classify(control("#b"))).toEqual({ kind: "unknown", reason: "attachment" });
    expect(classify(control("#c"))).toEqual({ kind: "unknown", reason: "attachment" });
    expect(classify(control("#d"))).toEqual({ kind: "data", key: "cv" });
  });
});

describe("what counts as hidden", () => {
  it("hides type=hidden, display:none, hidden, aria-hidden and visibility:hidden, on the control or an ancestor", () => {
    mount(`
      <input id="a" type="hidden">
      <input id="b" type="text" style="display:none">
      <div hidden><input id="c" type="text"></div>
      <div aria-hidden="true"><input id="d" type="text"></div>
      <div style="visibility:hidden"><input id="e" type="text"></div>
      <div style="display:none"><input id="f" type="text"></div>
      <input id="g" type="text">
    `);
    for (const id of ["#a", "#b", "#c", "#d", "#e", "#f"]) expect(isHidden(control(id)), id).toBe(true);
    expect(isHidden(control("#g"))).toBe(false);
  });

  it("treats a file input as present when only the input itself is hidden", () => {
    mount('<div><input id="a" type="file" style="display:none"></div><div hidden><input id="b" type="file"></div>');
    expect(isHidden(control("#a"))).toBe(false);
    expect(isHidden(control("#b"))).toBe(true);
  });

  it("never plans a hidden or read-only or disabled field", () => {
    mount('<label for="a">Email</label><input id="a" type="email" style="display:none"><label for="b">Phone</label><input id="b" type="tel" readonly><label for="c">First name</label><input id="c" type="text" disabled>');
    expect(planFill(document, sampleData(), "greenhouse")).toEqual([]);
  });
});

describe("labels and text", () => {
  it("reads aria-labelledby, aria-label, label[for], a wrapping label and the placeholder, in that order", () => {
    mount(`
      <span id="l1">Labelled by</span><input id="a" aria-labelledby="l1" aria-label="ignored">
      <input id="b" aria-label="Aria label">
      <label for="c">For label</label><input id="c">
      <label>Wrapping <input id="d"></label>
      <input id="e" placeholder="Placeholder">
    `);
    expect(labelOf(control("#a"))).toBe("Labelled by");
    expect(labelOf(control("#b"))).toBe("Aria label");
    expect(labelOf(control("#c"))).toBe("For label");
    expect(labelOf(control("#d"))).toBe("Wrapping");
    expect(labelOf(control("#e"))).toBe("Placeholder");
  });

  it("does not read a control's value or options as part of its label", () => {
    mount('<label>Country <select id="a"><option>Nigeria</option></select></label>');
    expect(labelOf(control("#a"))).toBe("Country");
  });

  it("normalises accents, punctuation, required markers and (for attributes) camelCase", () => {
    expect(normalise("  Prénom (required) * ")).toBe("prenom");
    expect(normalise("LinkedIn URL")).toBe("linkedin url");
    expect(normalise("legalNameSection_firstName", { camel: true })).toBe("legal name section first name");
    expect(normalise(undefined)).toBe("");
  });
});
