/**
 * The extension manifest (docs/adr/0035-chrome-extension.md), built by build.mjs and checked by tests/unit/manifest.test.ts.
 *
 * Minimal on purpose: the `storage` permission only (the access token lives in chrome.storage.session); no `tabs`, no
 * `activeTab`, no `<all_urls>`. Content scripts are declared for the four ATS hosts and nowhere else. The only host
 * permissions are the core-api origin and the object-storage origin the CV download link points to; both come from the
 * build, never from the page. No web_accessible_resources, no externally_connectable, no custom CSP (the MV3 default
 * forbids remote code, eval and inline script).
 */

/** Match patterns of the pages the content script runs on. */
export const ATS_MATCHES: readonly string[] = [
  // Greenhouse
  "https://boards.greenhouse.io/*",
  "https://job-boards.greenhouse.io/*",
  "https://boards.eu.greenhouse.io/*",
  "https://job-boards.eu.greenhouse.io/*",
  // Lever
  "https://jobs.lever.co/*",
  "https://jobs.eu.lever.co/*",
  // Ashby
  "https://jobs.ashbyhq.com/*",
  // Workday
  "https://*.myworkdayjobs.com/*",
];

export interface ManifestInput {
  version: string;
  /** e.g. http://localhost:8080 */
  coreApiUrl: string;
  /** Where pre-signed CV download links point, e.g. http://localhost:9000 */
  storageOrigin: string;
}

export interface Manifest {
  manifest_version: 3;
  name: string;
  description: string;
  version: string;
  minimum_chrome_version: string;
  permissions: string[];
  host_permissions: string[];
  background: { service_worker: string };
  action: { default_title: string; default_popup: string };
  content_scripts: { matches: string[]; js: string[]; run_at: "document_idle"; all_frames: false }[];
}

/** `https://host:port/*` for a URL; throws on anything that is not http(s) so a typo cannot widen the permission. */
export function originPattern(url: string): string {
  const parsed = new URL(url);
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    throw new Error(`Not an http(s) URL: ${url}`);
  }
  return `${parsed.protocol}//${parsed.host}/*`;
}

export function buildManifest(input: ManifestInput): Manifest {
  const hosts = [...new Set([originPattern(input.coreApiUrl), originPattern(input.storageOrigin)])];
  return {
    manifest_version: 3,
    name: "JobFinder autofill",
    description:
      "Fills the application form you have open from your JobFinder profile and application pack. It never submits.",
    version: input.version,
    minimum_chrome_version: "116",
    permissions: ["storage"],
    host_permissions: hosts,
    background: { service_worker: "background.js" },
    action: { default_title: "JobFinder", default_popup: "popup.html" },
    content_scripts: [{ matches: [...ATS_MATCHES], js: ["content.js"], run_at: "document_idle", all_frames: false }],
  };
}
