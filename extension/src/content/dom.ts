/**
 * Reading a form, deterministically. Everything here treats the page as untrusted data: text is read with textContent,
 * compared with fixed patterns, shown in the panel with textContent, and never sent anywhere, evaluated, or used to
 * build a selector (docs/adr/0035-chrome-extension.md).
 */

export type FormControl = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;

/**
 * Lowercase, accents folded, punctuation to single spaces. Attribute values (`camel: true`) also have camelCase split
 * ("legalNameSection_firstName" -> "legal name section first name"); visible text must not be ("LinkedIn" is one word).
 */
export function normalise(text: string | null | undefined, options: { camel?: boolean } = {}): string {
  if (!text) return "";
  const spaced = options.camel
    ? text.replace(/LinkedIn/g, "Linkedin").replace(/GitHub/g, "Github").replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    : text;
  return spaced
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[*]/g, " ")
    .replace(/\(\s*required\s*\)|\brequired\b|\boptional\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function textOf(node: Element | null): string {
  if (!node) return "";
  // A wrapping <label> contains the control: read its text without the control's own value or options.
  const copy = node.cloneNode(true) as Element;
  copy.querySelectorAll("input, select, textarea, option, script, style").forEach((n) => n.remove());
  return (copy.textContent ?? "").replace(/\s+/g, " ").trim();
}

function byId(root: Document, id: string): Element | null {
  return root.getElementById(id);
}

/** The human label of a control: aria-labelledby, aria-label, <label for>, a wrapping <label>, then the placeholder. */
export function labelOf(el: FormControl): string {
  const doc = el.ownerDocument;
  const labelledBy = el.getAttribute("aria-labelledby");
  if (labelledBy) {
    const text = labelledBy
      .split(/\s+/)
      .map((id) => textOf(byId(doc, id)))
      .filter(Boolean)
      .join(" ");
    if (text) return text;
  }
  const aria = el.getAttribute("aria-label")?.trim();
  if (aria) return aria;
  if (el.id) {
    const labels = Array.from(doc.querySelectorAll("label")).filter((l) => l.htmlFor === el.id);
    const text = labels.map(textOf).filter(Boolean).join(" ");
    if (text) return text;
  }
  const wrapping = el.closest("label");
  if (wrapping) {
    const text = textOf(wrapping);
    if (text) return text;
  }
  return el.getAttribute("placeholder")?.trim() ?? "";
}

/** The question a checkbox or radio belongs to: its fieldset's legend, a labelled group, or its own label. */
export function groupLabelOf(el: FormControl): string {
  const fieldset = el.closest("fieldset");
  const legend = fieldset?.querySelector("legend");
  if (legend) return textOf(legend);
  const group = el.closest('[role="group"], [role="radiogroup"]');
  const labelledBy = group?.getAttribute("aria-labelledby");
  if (labelledBy) {
    const text = labelledBy
      .split(/\s+/)
      .map((id) => textOf(byId(el.ownerDocument, id)))
      .filter(Boolean)
      .join(" ");
    if (text) return text;
  }
  const aria = group?.getAttribute("aria-label")?.trim();
  if (aria) return aria;
  return labelOf(el);
}

function layoutAvailable(doc: Document): boolean {
  const view = doc.defaultView;
  if (!view) return false;
  return doc.documentElement.getBoundingClientRect().width > 0 && view.innerWidth > 0;
}

function ancestorHidden(start: Element | null): boolean {
  for (let node = start; node; node = node.parentElement) {
    if (node.hasAttribute("hidden") || node.getAttribute("aria-hidden") === "true") return true;
    const style = node.ownerDocument.defaultView?.getComputedStyle(node);
    if (style && (style.display === "none" || style.visibility === "hidden")) return true;
  }
  return false;
}

/**
 * Whether a person could see and use the control. Hidden inputs, `display:none`/`hidden`/`aria-hidden` (on the control or
 * any ancestor), zero-size and parked-off-screen controls (honeypots) are hidden. A file input is the one exception to
 * "the control itself is invisible": sites hide the native input and show a button, so only its surroundings count.
 */
export function isHidden(el: FormControl): boolean {
  if (el instanceof HTMLInputElement && el.type === "hidden") return true;
  const isFile = el instanceof HTMLInputElement && el.type === "file";
  if (isFile) return ancestorHidden(el.parentElement);
  if (ancestorHidden(el)) return true;
  const doc = el.ownerDocument;
  if (layoutAvailable(doc)) {
    const rect = el.getBoundingClientRect();
    if (rect.width <= 1 && rect.height <= 1) return true;
    const view = doc.defaultView;
    const scrollX = view?.scrollX ?? 0;
    const scrollY = view?.scrollY ?? 0;
    if (rect.right + scrollX <= 0 || rect.bottom + scrollY <= 0) return true;
  }
  return false;
}

/** Every control a form could hold, in document order, with the ones of our own panel excluded (it lives in a shadow root). */
export function controls(root: ParentNode): FormControl[] {
  return Array.from(root.querySelectorAll<FormControl>("input, textarea, select"));
}
