import type { FillKey } from "./ats";
import { groupLabelOf, labelOf, normalise, type FormControl } from "./dom";

/**
 * Deciding what a control is, from fixed patterns over its label and attributes (label, name, id, autocomplete, aria,
 * data-automation-id). The order is the policy:
 *
 *   1. things that are never filled (password, captcha, sensitive questions, checkboxes and radios),
 *   2. contact data and links,
 *   3. free-text questions (the planner fills one only when it matches an answer from the pack closely),
 *   4. everything else is left for the user.
 *
 * Page text only ever flows into these patterns; nothing from the page is evaluated or used as a selector.
 */

export type SensitiveCategory = "demographic" | "work_authorization" | "legal_attestation" | "government_id";

export type Classification =
  | { kind: "never"; reason: "password" | "captcha" | "choice" }
  | { kind: "sensitive"; category: SensitiveCategory }
  | { kind: "data"; key: Exclude<FillKey, "screening"> }
  | { kind: "question" }
  | { kind: "unknown"; reason: "dropdown" | "attachment" | "unrecognised" };

const DEMOGRAPHIC =
  /\b(gender|sex|race|racial|ethnic\w*|hispanic|latino|latina|latinx|veterans?|military|armed forces|disabilit\w*|disabled|sexual orientation|orientation|transgender|lgbt\w*|pronouns?|demographic\w*|eeo\w*|equal employment|self identif\w*|protected (class|status|veteran)|religio\w*|marital|date of birth|birth ?dates?|dob|birthday|bday|age|national origin|ancestry|caste)\b/;
const WORK_AUTHORIZATION =
  /\b(authori[sz]ed|authori[sz]ation|legally|right to work|eligible to work|eligibility|sponsor\w*|visas?|immigration|work permits?|work status|citizen\w*|nationality|permanent residen\w*|green card|residency status|security clearance|clearance)\b/;
const LEGAL_ATTESTATION =
  /\b(certif\w*|attest\w*|acknowledg\w*|i agree|agree to|agreement|consent\w*|privacy (policy|notice|statement)|terms|conditions|gdpr|data protection|background checks?|criminal|convict\w*|felony|misdemeanor|non compete\w*|noncompete|conflict of interest|declar\w*|signature|sign here|i understand|i confirm|drug (test|screen)\w*|lawsuit|arrest\w*|under oath)\b/;
const GOVERNMENT_ID =
  /\b(ssn|social security|national id|national insurance|nin|passport|tax id|tin|id number|identification number|driver\w* licen[cs]e|licen[cs]e number|bvn|aadhaar|nric)\b/;

const CAPTCHA = /captcha|turnstile|g recaptcha response|h captcha response/;
const OTHER_PERSON = /\b(referr\w*|references?|manager|supervisor|recruiter|emergency|spouse|partner|friend|colleague|employer|company)\b/;

/** Input types that are not something a person fills in. */
const NOT_A_FIELD = new Set(["submit", "button", "reset", "image", "range", "color"]);
const TEXT_TYPES = new Set(["text", "search", ""]);

export function isFillableControl(el: FormControl): boolean {
  return !(el instanceof HTMLInputElement && NOT_A_FIELD.has(el.type));
}

function attrText(el: FormControl): string {
  return normalise(
    [
      el.getAttribute("name"),
      el.id,
      el.getAttribute("data-automation-id"),
      el.getAttribute("data-qa"),
      el.getAttribute("data-testid"),
    ]
      .filter(Boolean)
      .join(" "),
    { camel: true },
  );
}

function legendText(el: FormControl): string {
  return normalise(el.closest("fieldset")?.querySelector("legend")?.textContent);
}

function hasCaptchaAncestor(el: FormControl): boolean {
  return el.closest('.g-recaptcha, .h-captcha, .cf-turnstile, [class*="captcha" i], [id*="captcha" i]') !== null;
}

/** The sensitive category the control belongs to, judged on everything we can read about it. */
export function sensitiveCategory(el: FormControl): SensitiveCategory | null {
  const type = el instanceof HTMLInputElement ? el.type : "";
  const isChoice = type === "checkbox" || type === "radio";
  const everything = [
    normalise(isChoice ? groupLabelOf(el) : labelOf(el)),
    normalise(labelOf(el)),
    attrText(el),
    legendText(el),
    normalise(el.getAttribute("autocomplete")),
    normalise(el.getAttribute("placeholder")),
  ].join(" | ");
  if (GOVERNMENT_ID.test(everything)) return "government_id";
  if (DEMOGRAPHIC.test(everything)) return "demographic";
  if (WORK_AUTHORIZATION.test(everything)) return "work_authorization";
  if (LEGAL_ATTESTATION.test(everything)) return "legal_attestation";
  return null;
}

type DataKey = Exclude<FillKey, "screening">;

function contactKey(el: FormControl, label: string, attrs: string): DataKey | null {
  const type = el instanceof HTMLInputElement ? el.type : "";
  const autocomplete = (el.getAttribute("autocomplete") ?? "").toLowerCase().split(/\s+/).pop() ?? "";

  // A field about someone else (a referee, a manager) or the employer is never the candidate's own detail.
  if (OTHER_PERSON.test(label)) return null;
  // "Other website", "Other": not the one link we know about.
  if (/^other\b/.test(label)) return null;

  // Specific links first: "LinkedIn URL" is not a name, "Portfolio" is not a location.
  if (/linkedin/.test(label) || /linkedin/.test(attrs)) return "linkedin";
  if (/github/.test(label) || /github/.test(attrs)) return "github";
  if (
    /\b(portfolio|personal (web ?)?site|website|web site|blog|personal url)\b/.test(label) ||
    /\burls (portfolio|website|site)\b/.test(attrs)
  ) {
    return "portfolio";
  }

  const mapped: Record<string, DataKey> = {
    "given-name": "firstName",
    "family-name": "lastName",
    name: "fullName",
    email: "email",
    tel: "phone",
    "tel-national": "phone",
    "tel-local": "phone",
    "address-level2": "city",
  };
  const fromAutocomplete = mapped[autocomplete];
  if (fromAutocomplete) return fromAutocomplete;

  if (type === "email" || /\be ?mail\b/.test(label) || /\be ?mail\b/.test(attrs)) return "email";

  if (/^(legal )?(first|given) names?( s)?$|^first$|^forename$/.test(label) || /\b(first name|given name|firstname)\b/.test(attrs)) {
    return "firstName";
  }
  if (/^(legal )?(last|family) name$|^surname$|^last$/.test(label) || /\b(last name|family name|surname|lastname)\b/.test(attrs)) {
    return "lastName";
  }

  const notAPhoneNumber = /\b(type|country|code|extension|ext|device)\b/;
  if (
    type === "tel" ||
    (/\b(phone|mobile|telephone|cell|contact number)\b/.test(label) && !notAPhoneNumber.test(label)) ||
    (/\b(phone|mobile|tel|telephone)\b/.test(attrs) && !notAPhoneNumber.test(attrs))
  ) {
    return "phone";
  }

  if (
    /^((your|full|legal|candidate|applicant) )*name$/.test(label) ||
    /^(job application )?(full )?name$|^systemfield name$|^candidate name$/.test(attrs)
  ) {
    return "fullName";
  }

  if (/^(current |your |preferred )?city( and country)?$/.test(label) || /\bcity\b/.test(attrs)) {
    return /\b(location|country|state)\b/.test(label) ? "location" : "city";
  }
  if (
    /^(current |your |preferred )?location( city| and country)?$|^where are you (currently )?(based|located|living)/.test(label) ||
    /^(job application )?location( city)?$/.test(attrs)
  ) {
    return "location";
  }
  return null;
}

export function classify(el: FormControl): Classification {
  const type = el instanceof HTMLInputElement ? el.type : "";

  if (type === "password") return { kind: "never", reason: "password" };
  if (hasCaptchaAncestor(el) || CAPTCHA.test(attrText(el))) return { kind: "never", reason: "captcha" };

  const category = sensitiveCategory(el);
  if (category) return { kind: "sensitive", category };
  if (type === "checkbox" || type === "radio") return { kind: "never", reason: "choice" };

  if (el instanceof HTMLSelectElement) return { kind: "unknown", reason: "dropdown" };

  const label = normalise(labelOf(el));
  const attrs = attrText(el);

  if (type === "file") {
    const text = `${label} ${attrs} ${legendText(el)}`;
    if (/\bcover letter\b/.test(text)) return { kind: "unknown", reason: "attachment" };
    if (/\b(resume|cv|curriculum vitae)\b/.test(text)) return { kind: "data", key: "cv" };
    return { kind: "unknown", reason: "attachment" };
  }

  if (el instanceof HTMLTextAreaElement && (/\bcover letter\b/.test(label) || /\bcover letter\b/.test(attrs))) {
    return { kind: "data", key: "coverLetter" };
  }

  const key = contactKey(el, label, attrs);
  if (key) return { kind: "data", key };

  const freeText = el instanceof HTMLTextAreaElement || (el instanceof HTMLInputElement && TEXT_TYPES.has(el.type));
  if (freeText && label !== "") return { kind: "question" };

  return { kind: "unknown", reason: "unrecognised" };
}
