import { PANEL_OPEN } from "../shared/config";
import type { FillReport, ReportItem } from "../shared/types";
import { PANEL_CSS } from "./panel-style";

/**
 * The on-page panel. It lives in a shadow root (closed in every release build) so the page can neither read it nor style
 * it, is built with DOM calls and textContent only (page text appears in it, so never as HTML), and has buttons for the
 * user to press: nothing here runs by itself and nothing here submits the form.
 */

export interface PanelActions {
  fill(): void;
  overwrite(): void;
  undo(): void;
  logApplied(): void;
}

export interface PanelView {
  busy: boolean;
  message?: { tone: "info" | "error" | "success"; text: string };
  job?: { title: string; company: string } | null;
  report?: FillReport;
  /** The user can say "I submitted this application" (a fill happened and the job is known). */
  canLog: boolean;
  logged: boolean;
}

export interface Panel {
  render(view: PanelView): void;
  open(): void;
}

const MAX_LISTED = 40;

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: { text?: string; className?: string; attrs?: Record<string, string> } = {},
  children: Node[] = [],
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (props.text !== undefined) node.textContent = props.text;
  if (props.className) node.className = props.className;
  for (const [k, v] of Object.entries(props.attrs ?? {})) node.setAttribute(k, v);
  for (const child of children) node.append(child);
  return node;
}

function list(title: string, items: ReportItem[]): Node[] {
  const shown = items.slice(0, MAX_LISTED);
  const rows = shown.map((item) => el("li", {}, [el("strong", { text: item.label }), document.createTextNode(" "), el("span", { text: item.detail })]));
  if (items.length > shown.length) rows.push(el("li", { text: `and ${items.length - shown.length} more` }));
  return [el("h3", { text: `${title} (${items.length})` }), el("ul", {}, rows)];
}

export function createPanel(actions: PanelActions): Panel {
  const host = document.createElement("div");
  host.setAttribute("data-jobfinder-panel", "");
  const root = host.attachShadow({ mode: PANEL_OPEN ? "open" : "closed" });

  try {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(PANEL_CSS);
    root.adoptedStyleSheets = [sheet];
  } catch {
    const style = document.createElement("style");
    style.textContent = PANEL_CSS;
    root.append(style);
  }

  const wrap = el("div", { className: "wrap" });
  const pill = el("button", { text: "JobFinder", className: "pill", attrs: { type: "button", "aria-expanded": "false" } });
  const card = el("section", { className: "card", attrs: { "aria-label": "JobFinder autofill" } });
  card.hidden = true;
  wrap.append(card, pill);
  root.append(wrap);

  const setOpen = (open: boolean): void => {
    card.hidden = !open;
    pill.hidden = open;
    pill.setAttribute("aria-expanded", String(open));
  };
  pill.addEventListener("click", () => setOpen(true));

  const button = (text: string, onClick: () => void, opts: { primary?: boolean; disabled?: boolean } = {}): HTMLButtonElement => {
    const b = el("button", { text, className: opts.primary ? "primary" : "", attrs: { type: "button" } });
    b.disabled = opts.disabled ?? false;
    b.addEventListener("click", onClick);
    return b;
  };

  function render(view: PanelView): void {
    const close = button("Close", () => setOpen(false));
    close.setAttribute("aria-label", "Close panel");
    close.textContent = "×";
    const nodes: Node[] = [el("div", { className: "head" }, [el("strong", { text: "JobFinder autofill" }), close])];

    nodes.push(
      el("div", {
        className: `status ${view.message?.tone ?? ""}`,
        text: view.message?.text ?? "Press the button to fill this form from your JobFinder profile. You review it and submit it yourself.",
        attrs: { role: "status", "aria-live": "polite" },
      }),
    );
    if (view.job) nodes.push(el("p", { className: "job", text: `Matched job: ${view.job.title}${view.job.company ? ` at ${view.job.company}` : ""}` }));

    nodes.push(button(view.report ? "Fill again" : "Fill from JobFinder", actions.fill, { primary: true, disabled: view.busy }));

    if (view.report) {
      const { filled, skipped, overwritable } = view.report;
      nodes.push(...list("Filled", filled));
      nodes.push(...list("Needs your input", skipped));
      const row = el("div", { className: "row" });
      if (overwritable > 0) {
        row.append(button(`Overwrite ${overwritable} field${overwritable === 1 ? "" : "s"} that already ${overwritable === 1 ? "has" : "have"} a value`, actions.overwrite, { disabled: view.busy }));
      }
      row.append(button("Undo fill", actions.undo, { disabled: view.busy || filled.length === 0 }));
      nodes.push(row);
    }

    if (view.canLog) {
      nodes.push(el("hr", { className: "rule" }));
      nodes.push(el("p", { className: "note", text: view.logged ? "Logged in your JobFinder tracker as applied." : "Review the form and submit it yourself. After you have submitted it, you can log it:" }));
      if (!view.logged) nodes.push(el("div", { className: "row" }, [button("I submitted this application", actions.logApplied, { disabled: view.busy })]));
    }
    nodes.push(el("p", { className: "note", text: "JobFinder never submits a form, and never fills demographic, work-authorization, password, captcha or hidden fields." }));
    card.replaceChildren(...nodes);
  }

  render({ busy: false, canLog: false, logged: false });
  // On <html>, not <body>: single-page apps replace the body.
  document.documentElement.append(host);
  return { render, open: () => setOpen(true) };
}
