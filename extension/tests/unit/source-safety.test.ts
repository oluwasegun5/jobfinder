import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * Guardrails on the source itself (ADR 0035): the extension never submits, nothing evaluates page text, the access token
 * is only reachable from the service worker, and nothing is logged or stored outside chrome.storage.session.
 */

const SRC = resolve(import.meta.dirname, "../../src");

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

function stripComments(code: string): string {
  return code.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:"'`])\/\/.*$/gm, "$1");
}

const files = walk(SRC).filter((f) => /\.(ts|html)$/.test(f));
const sources = files.map((file) => ({ file: relative(SRC, file), code: stripComments(readFileSync(file, "utf8")) }));
const inDir = (dir: string) => sources.filter((s) => s.file.startsWith(`${dir}/`));

describe("the extension never submits a form", () => {
  const forbidden: [string, RegExp][] = [
    ["form.submit()", /\.submit\s*\(/],
    ["requestSubmit", /requestSubmit/],
    ["element.click()", /\.click\s*\(/],
    ["KeyboardEvent", /KeyboardEvent/],
    ["key events", /['"`](keydown|keypress|keyup)['"`]/],
    ["Enter key", /['"`]Enter['"`]/],
    ["synthetic pointer or mouse events", /new\s+(MouseEvent|PointerEvent|TouchEvent|SubmitEvent)/],
    ["a submit event", /['"`]submit['"`]\s*,\s*\{|new\s+Event\(\s*['"`]submit/],
  ];

  it("the scan finds source files", () => {
    expect(sources.length).toBeGreaterThan(10);
  });

  for (const [name, pattern] of forbidden) {
    it(`has no ${name}`, () => {
      const hits = sources.filter((s) => pattern.test(s.code)).map((s) => s.file);
      expect(hits).toEqual([]);
    });
  }

  it("only dispatches input and change events on the page", () => {
    const events = sources.flatMap((s) => [...s.code.matchAll(/new Event\(\s*["'`]([^"'`]+)["'`]/g)].map((m) => m[1]));
    expect(new Set(events)).toEqual(new Set(["input", "change"]));
  });
});

describe("page text is never evaluated or injected as markup", () => {
  const forbidden: [string, RegExp][] = [
    ["eval", /\beval\s*\(/],
    ["new Function", /new\s+Function\b/],
    ["setTimeout with a string", /setTimeout\s*\(\s*['"`]/],
    ["innerHTML", /\binnerHTML\b/],
    ["outerHTML", /\bouterHTML\b/],
    ["insertAdjacentHTML", /insertAdjacentHTML/],
    ["document.write", /document\.write/],
    ["executeScript", /executeScript/],
    ["importScripts", /importScripts/],
  ];
  for (const [name, pattern] of forbidden) {
    it(`has no ${name}`, () => {
      expect(sources.filter((s) => pattern.test(s.code)).map((s) => s.file)).toEqual([]);
    });
  }

  it("never builds a selector from the page's text", () => {
    // querySelector/closest take literals only: no template strings, no concatenation.
    const hits = sources.filter((s) => /(querySelector(All)?|closest|matches)\s*(<[^>]*>)?\(\s*(`|[^"'`\s)])/.test(s.code)).map((s) => s.file);
    expect(hits).toEqual([]);
  });
});

describe("the access token lives only in the service worker", () => {
  it("only src/background mentions the token or an Authorization header", () => {
    const hits = sources.filter((s) => /accessToken|Authorization|Bearer/.test(s.code)).map((s) => s.file.split("/")[0]);
    expect(new Set(hits)).toEqual(new Set(["background"]));
  });

  it("content scripts and the popup make no network calls and import nothing from the background", () => {
    for (const s of [...inDir("content"), ...inDir("popup")]) {
      expect(s.code, s.file).not.toMatch(/\bfetch\s*\(/);
      expect(s.code, s.file).not.toMatch(/XMLHttpRequest|WebSocket|sendBeacon|EventSource/);
      expect(s.code, s.file).not.toMatch(/from\s+["']\.\.\/background/);
      expect(s.code, s.file).not.toMatch(/@jobfinder\/api-contract/);
    }
  });

  it("only the service worker uses chrome.storage, and only the session area", () => {
    const users = sources.filter((s) => /chrome\.storage/.test(s.code)).map((s) => s.file);
    expect(users).toEqual(["background/session.ts"]);
    expect(sources.find((s) => s.file === "background/session.ts")!.code).not.toMatch(/storage\.(local|sync|managed)/);
    expect(sources.some((s) => /localStorage|sessionStorage|indexedDB|document\.cookie/.test(s.code))).toBe(false);
  });

  it("restricts the session area to trusted contexts", () => {
    expect(sources.find((s) => s.file === "background/session.ts")!.code).toMatch(/TRUSTED_CONTEXTS/);
  });

  it("only the three /auth calls ask the browser to send cookies", () => {
    const api = sources.find((s) => s.file === "background/api.ts")!.code;
    const includes = [...api.matchAll(/credentials:\s*"include"/g)].length;
    expect(includes).toBe(3);
    expect(api).toMatch(/credentials:\s*"omit"/);
  });
});

describe("nothing is logged", () => {
  it("has no console calls", () => {
    expect(sources.filter((s) => /\bconsole\s*\./.test(s.code)).map((s) => s.file)).toEqual([]);
  });
});
