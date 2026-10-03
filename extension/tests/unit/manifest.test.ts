import { describe, expect, it } from "vitest";

import { ATS_MATCHES, buildManifest, originPattern } from "../../src/manifest";

const manifest = buildManifest({ version: "1.2.3", coreApiUrl: "http://localhost:8080", storageOrigin: "http://localhost:9000" });

describe("manifest hygiene (ADR 0035)", () => {
  it("asks for the storage permission and nothing else", () => {
    expect(manifest.permissions).toEqual(["storage"]);
    const text = JSON.stringify(manifest);
    for (const forbidden of ["tabs", "activeTab", "<all_urls>", "webRequest", "cookies", "scripting", "downloads", "history"]) {
      expect(manifest.permissions).not.toContain(forbidden);
    }
    expect(text).not.toContain("<all_urls>");
    expect(text).not.toContain("*://*/*");
  });

  it("has host permissions for core-api and the object store only, each an exact origin", () => {
    expect(manifest.host_permissions).toEqual(["http://localhost:8080/*", "http://localhost:9000/*"]);
  });

  it("collapses a shared origin into one host permission", () => {
    const m = buildManifest({ version: "1", coreApiUrl: "http://127.0.0.1:18787", storageOrigin: "http://127.0.0.1:18787/files" });
    expect(m.host_permissions).toEqual(["http://127.0.0.1:18787/*"]);
  });

  it("declares content scripts for the four ATS hosts, https only, top frame only", () => {
    expect(manifest.content_scripts).toHaveLength(1);
    const script = manifest.content_scripts[0]!;
    expect(script.matches).toEqual([...ATS_MATCHES]);
    for (const pattern of script.matches) {
      expect(pattern).toMatch(/^https:\/\/(\*\.myworkdayjobs\.com|(job-)?boards\.(eu\.)?greenhouse\.io|jobs\.(eu\.)?lever\.co|jobs\.ashbyhq\.com)\/\*$/);
    }
    expect(script.all_frames).toBe(false);
    expect(script.run_at).toBe("document_idle");
  });

  it("has no remote code, no custom CSP, no external connections, no web-accessible resources", () => {
    const m = manifest as unknown as Record<string, unknown>;
    expect(m.content_security_policy).toBeUndefined();
    expect(m.externally_connectable).toBeUndefined();
    expect(m.web_accessible_resources).toBeUndefined();
    expect(m.sandbox).toBeUndefined();
    expect(m.update_url).toBeUndefined();
  });

  it("is a Manifest V3 service-worker extension", () => {
    expect(manifest.manifest_version).toBe(3);
    expect(manifest.background).toEqual({ service_worker: "background.js" });
    expect(manifest.action.default_popup).toBe("popup.html");
  });

  it("refuses an origin that is not http(s)", () => {
    expect(() => originPattern("file:///etc/passwd")).toThrow();
    expect(() => originPattern("javascript:alert(1)")).toThrow();
    expect(originPattern("https://api.example.test:8443/v1")).toBe("https://api.example.test:8443/*");
  });
});
