import type { AtsName, FillData, UsedDocuments } from "../shared/types";
import { ALLOWED, type FillKey } from "./ats";
import { classify, isFillableControl, type SensitiveCategory } from "./classify";
import { controls, groupLabelOf, isHidden, labelOf, type FormControl } from "./dom";
import { matchScreening } from "./screening";

/**
 * What to do with every control on the page, decided before anything is touched. Pure reading: this module never
 * changes the page (apply.ts does, and only for the items marked "fill").
 */

export type SkipCode =
  | "sensitive"
  | "password"
  | "captcha"
  | "choice"
  | "has_value"
  | "no_data"
  | "not_on_this_site"
  | "no_match"
  | "dropdown"
  | "attachment"
  | "unrecognised";

export interface PlanItem {
  el: FormControl;
  /** Page text, for display in the panel only. */
  label: string;
  action: "fill" | "skip";
  key?: FillKey;
  /** The text to write; absent for the CV (a file) and for skips. */
  value?: string;
  /** The skipped field's reason. */
  skip?: SkipCode;
  category?: SensitiveCategory;
}

const SENSITIVE_TEXT: Record<SensitiveCategory, string> = {
  demographic: "Demographic / equal-opportunity question: never filled",
  work_authorization: "Work authorization or sponsorship question: never filled",
  legal_attestation: "Legal statement or consent: never filled",
  government_id: "Government ID: never filled",
};

const SKIP_TEXT: Record<Exclude<SkipCode, "sensitive">, string> = {
  password: "Password field: never filled",
  captcha: "Captcha: never filled",
  choice: "Checkboxes and radio buttons are never selected for you",
  has_value: "Already has a value you typed",
  no_data: "Nothing in your JobFinder profile for this field",
  not_on_this_site: "Not filled on this site",
  no_match: "A question to answer yourself",
  dropdown: "Dropdown: choose yourself",
  attachment: "Attachment: add it yourself",
  unrecognised: "Not recognised: needs your input",
};

export function skipText(item: Pick<PlanItem, "skip" | "category">, ats?: AtsName): string {
  if (item.skip === "sensitive" && item.category) return SENSITIVE_TEXT[item.category];
  if (item.skip === "not_on_this_site") {
    return ats === "workday"
      ? "Workday: only contact details are filled"
      : ats === "ashby"
        ? "Ashby: only contact details and links are filled"
        : SKIP_TEXT.not_on_this_site;
  }
  if (item.skip && item.skip !== "sensitive") return SKIP_TEXT[item.skip];
  return SKIP_TEXT.unrecognised;
}

export const KEY_TEXT: Record<FillKey, string> = {
  fullName: "Full name",
  firstName: "First name",
  lastName: "Last name",
  email: "Email",
  phone: "Phone",
  location: "Location",
  city: "City",
  linkedin: "LinkedIn",
  github: "GitHub",
  portfolio: "Portfolio / website",
  coverLetter: "Cover letter",
  cv: "CV (file)",
  screening: "Answer from your application pack",
};

export interface PlanOptions {
  /** Replace values that are already there. Off unless the user clicked "Overwrite". */
  overwrite?: boolean;
}

function valueFor(key: FillKey, data: FillData): string | undefined {
  const c = data.contact;
  switch (key) {
    case "fullName":
      return c.fullName;
    case "firstName":
      return c.firstName;
    case "lastName":
      return c.lastName;
    case "email":
      return c.email;
    case "phone":
      return c.phone;
    case "location":
      return c.location;
    case "city":
      return c.location?.split(",")[0]?.trim() || undefined;
    case "linkedin":
      return c.linkedin;
    case "github":
      return c.github;
    case "portfolio":
      return c.portfolio;
    case "coverLetter":
      return data.coverLetter ?? undefined;
    case "cv":
      return data.cv ? "file" : undefined;
    case "screening":
      return undefined;
  }
}

function hasValue(el: FormControl): boolean {
  if (el instanceof HTMLInputElement && el.type === "file") return (el.files?.length ?? 0) > 0;
  return el.value.trim() !== "";
}

export function planFill(root: ParentNode, data: FillData, ats: AtsName, options: PlanOptions = {}): PlanItem[] {
  const items: PlanItem[] = [];
  const seenGroups = new Set<string>();

  for (const el of controls(root)) {
    if (!isFillableControl(el) || el.disabled || isHidden(el)) continue;
    if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
      if (el.readOnly) continue;
    }

    const isChoice = el instanceof HTMLInputElement && (el.type === "checkbox" || el.type === "radio");
    const label = (isChoice ? groupLabelOf(el) : labelOf(el)).replace(/\s+/g, " ").trim().slice(0, 140);
    if (isChoice) {
      // One line per question, not one per option.
      const group = `${el.getAttribute("name") ?? ""}|${label}`;
      if (seenGroups.has(group)) continue;
      seenGroups.add(group);
    }
    const shown = label || el.getAttribute("name") || el.id || "Unlabelled field";
    const skip = (code: SkipCode, category?: SensitiveCategory): void => {
      items.push({ el, label: shown, action: "skip", skip: code, category });
    };

    const cls = classify(el);
    switch (cls.kind) {
      case "never":
        skip(cls.reason);
        break;
      case "sensitive":
        skip("sensitive", cls.category);
        break;
      case "unknown":
        skip(cls.reason);
        break;
      case "question": {
        if (!ALLOWED[ats].has("screening")) {
          skip("not_on_this_site");
          break;
        }
        const answer = matchScreening(label, data.screening);
        if (!answer) {
          skip("no_match");
          break;
        }
        if (hasValue(el) && !options.overwrite) {
          skip("has_value");
          break;
        }
        items.push({ el, label: shown, action: "fill", key: "screening", value: answer.answer });
        break;
      }
      case "data": {
        if (!ALLOWED[ats].has(cls.key)) {
          skip("not_on_this_site");
          break;
        }
        const value = valueFor(cls.key, data);
        if (value === undefined) {
          skip("no_data");
          break;
        }
        if (hasValue(el) && !options.overwrite) {
          skip("has_value");
          break;
        }
        items.push({ el, label: shown, action: "fill", key: cls.key, value: cls.key === "cv" ? undefined : value });
        break;
      }
    }
  }
  return items;
}

/** Only the documents whose content really went into the form are recorded as used. */
export function usedFrom(offered: UsedDocuments, filled: readonly PlanItem[]): UsedDocuments {
  const keys = new Set(filled.map((i) => i.key));
  return {
    ...(keys.has("cv") && offered.resumeDocumentId ? { resumeDocumentId: offered.resumeDocumentId } : {}),
    ...(keys.has("coverLetter") && offered.coverLetterDocumentId ? { coverLetterDocumentId: offered.coverLetterDocumentId } : {}),
    ...(keys.has("screening") && offered.screeningAnswersDocumentId
      ? { screeningAnswersDocumentId: offered.screeningAnswersDocumentId }
      : {}),
  };
}
