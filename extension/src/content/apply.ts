import type { FormControl } from "./dom";
import type { PlanItem } from "./plan";

/**
 * Writing the planned values into the page, highlighting them, and taking them back. The only things this module does to
 * a page are: set a value (and fire `input` and `change` so the page's own scripts notice), attach a file to a file
 * input, and mark/unmark the control with an outline. It never clicks, never presses a key and never submits.
 */

export const FILLED_ATTRIBUTE = "data-jobfinder-filled";

const OUTLINE = "3px solid #16a34a";

export interface CvFilePayload {
  name: string;
  contentType: string;
  base64: string;
}

interface UndoEntry {
  el: FormControl;
  previousValue: string;
  previousFiles: File[];
  previousOutline: string;
  previousOutlineOffset: string;
  highlighted: HTMLElement;
}

function setText(el: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  // The prototype's setter, not the instance's: frameworks that track the value on the element itself then see a change.
  const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
  if (setter) setter.call(el, value);
  else el.value = value;
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
}

function setFiles(el: HTMLInputElement, files: File[]): void {
  const transfer = new DataTransfer();
  for (const file of files) transfer.items.add(file);
  el.files = transfer.files;
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
}

export function base64ToBytes(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** A native file input is often invisible; outline the thing the person sees (its container) instead. */
function highlightTarget(el: FormControl): HTMLElement {
  if (el instanceof HTMLInputElement && el.type === "file") {
    return el.closest<HTMLElement>("label, fieldset, .field, [class*='upload' i]") ?? el.parentElement ?? el;
  }
  return el;
}

/** The fills of one page, so that "Undo fill" can put every control back as it was. */
export class FillSession {
  private readonly entries = new Map<FormControl, UndoEntry>();

  get count(): number {
    return this.entries.size;
  }

  /**
   * Fills the items marked "fill". A control filled before keeps its original undo entry, so undoing twice-filled
   * controls (after "Overwrite") goes back to what the user had, not to our first fill. Returns the items it filled.
   */
  apply(items: readonly PlanItem[], cv: CvFilePayload | null): PlanItem[] {
    const filled: PlanItem[] = [];
    for (const item of items) {
      if (item.action !== "fill") continue;
      const el = item.el;
      const isCv = item.key === "cv";
      const writable = isCv
        ? cv !== null && el instanceof HTMLInputElement
        : item.value !== undefined && (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement);
      if (!writable) continue;
      if (!this.entries.has(el)) this.remember(el);
      if (isCv && cv && el instanceof HTMLInputElement) {
        setFiles(el, [new File([base64ToBytes(cv.base64)], cv.name, { type: cv.contentType })]);
      } else if (item.value !== undefined && (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) {
        setText(el, item.value);
      }
      this.mark(el, item.key ?? "");
      filled.push(item);
    }
    return filled;
  }

  /** Restores every control; returns how many it restored. */
  undo(): number {
    let restored = 0;
    for (const entry of this.entries.values()) {
      const { el } = entry;
      if (el instanceof HTMLInputElement && el.type === "file") setFiles(el, entry.previousFiles);
      else if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) setText(el, entry.previousValue);
      entry.highlighted.style.outline = entry.previousOutline;
      entry.highlighted.style.outlineOffset = entry.previousOutlineOffset;
      el.removeAttribute(FILLED_ATTRIBUTE);
      restored += 1;
    }
    this.entries.clear();
    return restored;
  }

  private remember(el: FormControl): void {
    const highlighted = highlightTarget(el);
    this.entries.set(el, {
      el,
      previousValue: el instanceof HTMLSelectElement ? "" : el instanceof HTMLInputElement && el.type === "file" ? "" : el.value,
      previousFiles: el instanceof HTMLInputElement && el.type === "file" ? Array.from(el.files ?? []) : [],
      previousOutline: highlighted.style.outline,
      previousOutlineOffset: highlighted.style.outlineOffset,
      highlighted,
    });
  }

  private mark(el: FormControl, key: string): void {
    const entry = this.entries.get(el);
    if (!entry) return;
    entry.highlighted.style.outline = OUTLINE;
    entry.highlighted.style.outlineOffset = "2px";
    el.setAttribute(FILLED_ATTRIBUTE, key);
  }
}
