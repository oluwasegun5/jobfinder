# 0014. Web auth client

## Status
Accepted

## Context
P1.3 adds the sign-up, login, verification, reset and session UI on top of the core-api identity
endpoints (ADR 0012). CLAUDE.md requires the access token in memory and the refresh token in an httpOnly
cookie, and ADR 0012 warns that concurrent refreshes with the same token trip reuse detection.

## Decision
- **Session module** (`lib/auth/session.ts`): the access token is a module variable, never written to web
  storage. `refreshSession()` is single-flight, so page load, the expiry timer and a 401 retry can never send
  two refreshes at once. Only a 401/403 from `/auth/refresh` ends the session; a network error or 5xx leaves it.
- **Silent refresh**: `AuthProvider` refreshes on first load (the cookie is all that survives a reload), then
  again 60 s before each access token expires. An openapi-fetch middleware adds the bearer header and, on a
  401, refreshes once and replays the request.
- **Route protection is client-side.** The token is not readable by the server, so Next cannot know who is
  signed in. `RequireAuth` wraps the `(app)` group and redirects to `/login?next=<path>`; `GuestOnly` wraps the
  `(auth)` group. `next` accepts same-origin paths only. This guards the UI, not data: core-api still
  enforces auth on every endpoint.
- **Google sign-in** uses Google Identity Services and posts the ID token to `/auth/google`. The button renders
  only when `NEXT_PUBLIC_GOOGLE_CLIENT_ID` (build-time, wired from `GOOGLE_CLIENT_ID` in compose) is set.
- **Plain forms, no form library.** Five short forms rely on native validation plus server errors, so
  react-hook-form/zod are not added yet.
- **E2E (Playwright)** runs against a production build of the web app on port 3100 (dev-mode recompiles reload
  the page mid-test) with the compose backend. Verification and reset links are read from Mailpit. Because
  signup is rate limited to 5/h per IP, `E2E_RESET_RATE_LIMIT=1` makes `global-setup` delete the rate-limit keys (`rl:*`)
  of the compose project `E2E_COMPOSE_PROJECT` (default `jobfinder`); it is opt-in and never flushes Redis (see
  `apps/web/e2e/README.md`). Run with `npm run web:e2e` after `make up`.

## Consequences
- A reload briefly shows a loading state on protected pages while the silent refresh runs.
- Playwright browsers must be installed once (`npx playwright install chromium` in `apps/web`).
- E2E is not yet part of CI (needs the full stack); add it when the CI pipeline gains a compose job.
