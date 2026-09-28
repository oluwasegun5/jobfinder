# 0011. Web → core-api access and the API contract package

## Status
Accepted

## Context
P0.4 adds `apps/web` and `packages/api-contract`. PLAN.md fixes the stack (Next.js, openapi-typescript
+ openapi-fetch, generated client only) but leaves open how the browser reaches core-api, how the
two packages are wired together, and where the spec lives.

## Decision
- **npm workspaces** at the repo root (`apps/*`, `packages/*`) with one `package-lock.json`. npm ships
  with Node, so no extra tool to install; pnpm/Turborepo can come later if build times demand it.
- **Same-origin proxy.** The browser calls `/api/core/*` on the web origin; a Next.js rewrite forwards
  it to core-api. No CORS configuration, and the refresh-token cookie (P1.1) stays first-party and
  `SameSite=Strict`-friendly. `next.config` is serialized at build, so the target comes from the
  `CORE_API_URL` build arg (`http://core-api:8080` in compose, `http://localhost:8080` for `next dev`).
- **Committed spec and types.** `packages/api-contract/openapi.json` (with `servers` stripped, since
  springdoc derives it from the request host) and the generated `src/schema.d.ts` are committed. Builds
  and Docker images never need a running core-api, and CI (P0.5) can detect a stale client by diffing.
- **Package consumed as TypeScript source** (`exports` → `src/index.ts`, `transpilePackages` in Next).
  No separate build step.
- **Actuator in the spec.** `springdoc.show-actuator: true` so `/actuator/health` is part of the contract
  and the web health check goes through the generated client. Actuator publishes no response schema,
  so the web narrows `status` at runtime.
- **shadcn/ui components import `cn` from `@/lib/utils`** (clsx + tailwind-merge). The shadcn 4.21 CLI
  rewrote imports to a third-party npm package named `cn`; that dependency was removed.

## Consequences
- Changing core-api's public URL for the web container means rebuilding the web image.
- Deploying web on Vercel (PLAN.md §12) keeps working: the rewrite target is set as a build env var.
- Any API change needs `npm run contract:generate` and both generated files committed.
- `/v3/api-docs` and Swagger UI are publicly exposed by core-api; disable or gate them before production (Phase 6).
