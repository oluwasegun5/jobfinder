// Replaced at build time by build.mjs (esbuild `define`); see docs/adr/0035-chrome-extension.md.

/** The core-api origin: the only server the extension talks to, and its only host permission besides storage. */
declare const __CORE_API_URL__: string;
/** True only in the end-to-end test build: lets Playwright see into the panel's shadow root. Never in a release. */
declare const __PANEL_OPEN__: boolean;

export const CORE_API_URL: string = __CORE_API_URL__;
export const PANEL_OPEN: boolean = __PANEL_OPEN__;
