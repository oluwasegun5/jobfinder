// Pulls the OpenAPI spec from a running core-api and writes it to openapi.json.
// Usage: CORE_API_URL=http://localhost:8080 npm run fetch-spec
import { writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const baseUrl = (process.env.CORE_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const specUrl = `${baseUrl}/v3/api-docs`;
const outFile = fileURLToPath(new URL("../openapi.json", import.meta.url));

let response;
try {
  response = await fetch(specUrl, { signal: AbortSignal.timeout(15_000) });
} catch (error) {
  console.error(`Could not reach core-api at ${specUrl}. Is it running? (${error.message})`);
  process.exit(1);
}
if (!response.ok) {
  console.error(`GET ${specUrl} returned ${response.status}`);
  process.exit(1);
}

const spec = await response.json();
// springdoc derives `servers` from the request host, which would make the committed
// spec depend on where it was fetched from. Clients set their own base URL.
delete spec.servers;

await writeFile(outFile, `${JSON.stringify(spec, null, 2)}\n`);
console.log(`Wrote ${outFile}`);
