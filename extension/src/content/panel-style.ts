/** The panel's styles. Applied with a constructable stylesheet, which a page's Content-Security-Policy cannot block. */
export const PANEL_CSS = `
:host { all: initial; }
.wrap { position: fixed; right: 16px; bottom: 16px; z-index: 2147483647; font: 14px/1.4 system-ui, -apple-system, "Segoe UI", sans-serif; color: #111827; }
button { font: inherit; cursor: pointer; border-radius: 8px; border: 1px solid #d1d5db; background: #fff; color: #111827; padding: 8px 12px; }
button:hover { background: #f3f4f6; }
button:focus-visible { outline: 3px solid #2563eb; outline-offset: 2px; }
button[disabled] { opacity: .6; cursor: default; }
button.primary { background: #166534; border-color: #166534; color: #fff; }
button.primary:hover { background: #14532d; }
.pill { box-shadow: 0 2px 8px rgba(0,0,0,.25); border-radius: 999px; padding: 8px 14px; background: #166534; color: #fff; border-color: #166534; }
.card { width: min(360px, calc(100vw - 32px)); max-height: min(560px, calc(100vh - 32px)); overflow: auto; background: #fff; border: 1px solid #d1d5db; border-radius: 12px; box-shadow: 0 8px 28px rgba(0,0,0,.28); padding: 14px; }
.card[hidden] { display: none; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.head strong { font-size: 15px; }
.status { margin: 6px 0 10px; padding: 8px 10px; border-radius: 8px; background: #f3f4f6; }
.status.error { background: #fee2e2; color: #7f1d1d; }
.status.success { background: #dcfce7; color: #14532d; }
.job { margin: 0 0 10px; color: #374151; overflow-wrap: anywhere; }
h3 { font-size: 13px; margin: 12px 0 4px; }
ul { margin: 0; padding-left: 18px; }
li { margin: 2px 0; overflow-wrap: anywhere; }
li span { color: #4b5563; }
.row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.rule { border: 0; border-top: 1px solid #e5e7eb; margin: 14px 0 10px; }
.note { margin: 8px 0 0; font-size: 12px; color: #4b5563; }
`;
