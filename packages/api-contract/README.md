# @jobfinder/api-contract

core-api's OpenAPI spec and the typed TypeScript client generated from it
([openapi-typescript](https://openapi-ts.dev) + [openapi-fetch](https://openapi-ts.dev/openapi-fetch/)).
The web app talks to core-api **only** through this package (see `CLAUDE.md`).

| File | What it is |
|---|---|
| `openapi.json` | Spec pulled from core-api `/v3/api-docs` (committed; `servers` stripped) |
| `src/schema.d.ts` | Types generated from `openapi.json` (committed, never edit by hand) |
| `src/index.ts` | `createApiClient()` — an openapi-fetch client typed with `paths` |

## Regenerating after an API change

With core-api running (e.g. `make up`):

```bash
npm run contract:generate
```

This fetches the spec from `CORE_API_URL` (default `http://localhost:8080`) and regenerates
the types. Commit both `openapi.json` and `src/schema.d.ts`. To regenerate types from the
committed spec without a running core-api: `npm run codegen -w @jobfinder/api-contract`.

## Usage

```ts
import { createApiClient } from "@jobfinder/api-contract";

const api = createApiClient({ baseUrl: "/api/core" });
const { data, error } = await api.GET("/actuator/health");
```
