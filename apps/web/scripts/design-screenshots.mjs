// Design review screenshots (ADR 0043): landing and signup, phone and desktop, light and dark.
// Run against a started web app: `node scripts/design-screenshots.mjs http://localhost:3123`.
// Writes PNGs to docs/design/screenshots/ at the repository root.
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "@playwright/test";

const baseUrl = process.argv[2] ?? "http://localhost:3123";
const outDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../docs/design/screenshots");

const pages = [
  { name: "landing", path: "/" },
  { name: "signup", path: "/signup" },
];
const viewports = [
  { name: "mobile", width: 390, height: 844, isMobile: true, deviceScaleFactor: 2 },
  { name: "desktop", width: 1280, height: 800, isMobile: false, deviceScaleFactor: 1 },
];
const schemes = ["light", "dark"];

await mkdir(outDir, { recursive: true });
const browser = await chromium.launch();
try {
  for (const viewport of viewports) {
    for (const colorScheme of schemes) {
      const context = await browser.newContext({
        viewport: { width: viewport.width, height: viewport.height },
        isMobile: viewport.isMobile,
        hasTouch: viewport.isMobile,
        deviceScaleFactor: viewport.deviceScaleFactor,
        colorScheme,
        reducedMotion: "reduce",
      });
      const page = await context.newPage();
      for (const target of pages) {
        await page.goto(new URL(target.path, baseUrl).toString(), { waitUntil: "networkidle" });
        await page.getByRole("heading", { level: 1 }).first().waitFor();
        await page.evaluate(() => document.fonts.ready);
        const file = path.join(outDir, `${target.name}-${viewport.name}-${colorScheme}.png`);
        await page.screenshot({ path: file, fullPage: true });
        console.log(file);
      }
      await context.close();
    }
  }
} finally {
  await browser.close();
}
