// Builds the unpacked extension into dist/ (or $OUT_DIR). See README.md and docs/adr/0035-chrome-extension.md.
//
//   CORE_API_URL    core-api origin the extension talks to        (default http://localhost:8080)
//   STORAGE_ORIGIN  origin of pre-signed CV download links         (default http://localhost:9000)
//   OUT_DIR         output directory                               (default dist)
//   PANEL_OPEN      "true" only for the end-to-end test build: opens the panel's shadow root so Playwright can see it
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { build } from "esbuild";

const root = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(root, process.env.OUT_DIR ?? "dist");
const coreApiUrl = (process.env.CORE_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const storageOrigin = process.env.STORAGE_ORIGIN ?? "http://localhost:9000";
const panelOpen = process.env.PANEL_OPEN === "true";

const pkg = JSON.parse(await readFile(resolve(root, "package.json"), "utf8"));

// The manifest is TypeScript (so tests can check it); bundle it in memory and import the result.
const manifestBundle = await build({
  entryPoints: [resolve(root, "src/manifest.ts")],
  bundle: true,
  format: "esm",
  platform: "neutral",
  write: false,
  logLevel: "silent",
});
const manifestSource = manifestBundle.outputFiles[0].text;
const { buildManifest } = await import(`data:text/javascript;base64,${Buffer.from(manifestSource).toString("base64")}`);
const manifest = buildManifest({ version: pkg.version, coreApiUrl, storageOrigin });

await rm(outDir, { recursive: true, force: true });
await mkdir(outDir, { recursive: true });

await build({
  entryPoints: {
    background: resolve(root, "src/background/index.ts"),
    content: resolve(root, "src/content/index.ts"),
    popup: resolve(root, "src/popup/popup.ts"),
  },
  outdir: outDir,
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome116",
  sourcemap: false,
  minify: false,
  legalComments: "none",
  logLevel: "warning",
  define: {
    __CORE_API_URL__: JSON.stringify(coreApiUrl),
    __PANEL_OPEN__: JSON.stringify(panelOpen),
  },
});

await copyFile(resolve(root, "src/popup/popup.html"), resolve(outDir, "popup.html"));
await copyFile(resolve(root, "src/popup/popup.css"), resolve(outDir, "popup.css"));
await writeFile(resolve(outDir, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);

console.log(`Built ${manifest.name} ${manifest.version} -> ${outDir}`);
console.log(`  core-api: ${coreApiUrl}   storage: ${storageOrigin}   panel shadow root: ${panelOpen ? "OPEN (test build)" : "closed"}`);
