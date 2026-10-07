# Security review (P6.2): OWASP ASVS L2

Scope: core-api, ai-service, web, the Chrome extension, `infra/docker`, compose files and CI, at the tip of branch
`feat/p6-security`. Decisions are in [ADR 0037](adr/0037-security-hardening.md). ASVS references are to 4.0.3.

How to read this: every finding below was fixed in code, and its evidence is a test that failed before the change and passes
now (the test class is named; core-api tests live under `services/core-api/src/test/java/com/jobfinder/core/`). Items marked
*reasoned* were checked by reading code or configuration and have no test of their own. Nothing was probed outside this
machine.

## Findings

Result: 21 found, 21 fixed, 0 open. No critical. High 7, medium 8, low 5, informational 1.

| Id | Area | ASVS | Severity | Status | Evidence / test |
|---|---|---|---|---|---|
| SEC-01 | core-api: no security headers on API responses | 14.4.3, 14.4.4, 14.4.5, 14.4.6, 8.3.1 | medium | fixed | `identity/SecurityHeadersAndCorsTests` (header set on authenticated JSON, on filter-chain errors, HSTS over TLS only) |
| SEC-02 | core-api: CORS not an explicit allow-list | 14.5.3 | medium | fixed | `identity/SecurityHeadersAndCorsTests` (allowed, disallowed, `null`, preflight, extension origin) |
| SEC-03 | Cookie endpoints (`/auth/refresh`, `/auth/logout`) relied on SameSite alone | 4.2.2, 13.2.3 | medium | fixed | `identity/SecurityHeadersAndCorsTests` (same-origin ok, cross-site 403, no-header rule, extension origin) |
| SEC-04 | Expensive endpoints had no per-class rate limits (AI, upload, download, search, export, extension) | 11.1.4, 2.2.1 | high | fixed | `identity/internal/EndpointRateLimitTests` (every handler mapping classified; 429 + `Retry-After`; limits per key) |
| SEC-05 | Per-IP limits keyed on the web proxy address, so every visitor shared one bucket | 11.1.4, 2.2.1 | high | fixed | `identity/ClientIpRateLimitTests`, `identity/internal/ClientIpResolverTests` |
| SEC-06 | Uploads: no scanner hook, declared type not tied to content, macro and archive formats accepted | 12.1.1, 12.2.1, 12.3.1, 12.4.1 | high | fixed | `profile/UploadSafetyTests`, `profile/UploadScannerIntegrationTests`, `profile/UploadScannerFailOpenTests`, `storage/internal/ClamAvUploadScannerTests` |
| SEC-07 | Stored files could be served inline or with long-lived links | 12.5.1, 12.5.2 | medium | fixed | `profile/UploadSafetyTests` (attachment, nosniff, capped signed-URL lifetime) |
| SEC-08 | 9 endpoints had no registered ownership test; nothing prevented a new one shipping untested | 4.2.1, 4.1.3 | medium | fixed (no IDOR found) | `EndpointOwnershipGuardTests`, `AdminAndInternalAccessGuardTests` |
| SEC-09 | web: no CSP, and `/internal/**` and actuator paths were proxied to the browser | 14.4.1, 14.4.3, 14.5.x | high | fixed | `apps/web/src/lib/security/edge.test.ts`, `apps/web/e2e/security.spec.ts` |
| SEC-10 | web: HSTS missing when served over TLS | 14.4.5 | low | fixed | `apps/web/src/lib/security/edge.test.ts` |
| SEC-11 | Signup timing revealed whether an address was registered | 2.2.1, 3.2.x | low | fixed | `identity/SignupAndVerificationTests` (password hashed before the lookup) |
| SEC-12 | Access tokens carried no audience | 3.5.3 | low | fixed | `identity/LoginAndAccessTokenTests` (wrong or missing `aud` refused) |
| SEC-13 | Ingestion clients fetched provider URLs with no SSRF guard | 12.6.1, 5.2.6 | high | fixed | `shared/SsrfGuardTests`, `ingestion/internal/ats/AtsSsrfTests`, `ingestion/internal/aggregator/AggregatorSsrfTests` (private, loopback, link-local, metadata, IPv6-mapped, redirects) |
| SEC-14 | Swagger UI, API description and actuator detail reachable in production | 14.3.2, 14.3.3, 7.4.1 | medium | fixed | `InformationExposureTests` (prod profile) |
| SEC-15 | Database, broker and storage passwords had usable defaults in compose and `application.yml` | 2.10.4, 14.1.x | high | fixed | `SecretsConfigTests` |
| SEC-16 | Maven dependencies with published advisories (Tomcat, Jackson 2 and 3, RabbitMQ client, jsoup 1.23.1) | 14.2.1 | high | fixed | `pom.xml` overrides; OSV scan: "no known vulnerabilities" over 287 resolved artifacts |
| SEC-17 | npm: `source-map-js` 1.2.1 (event-loop DoS, GHSA-68fv-2mgg-jv7q) in the production tree | 14.2.1 | medium | fixed | upgraded to 1.2.2; `npm audit --omit=dev --audit-level=high`: 0 vulnerabilities |
| SEC-18 | `shadcn` (build-time CLI) shipped among web production dependencies | 14.2.1, 14.2.4 | low | fixed | `apps/web/package.json`; production audit shows none of its subtree |
| SEC-19 | core-api container ran as root; no production compose overlay (published data-store ports, no capability drops) | 14.1.x | medium | fixed | `ContainerHygieneTests` (non-root final stage, pinned tags, loopback dev ports, prod overlay resets ports, drops capabilities, read-only roots) |
| SEC-20 | Logs: no test that secrets and personal data stay out of logs | 7.1.1, 7.1.2 | informational | fixed (test added, redaction layer is a residual) | `identity/LogRedactionTests` |
| SEC-21 | web: the edge block on `/actuator/**` (SEC-09) also stopped the app's own status badge, which reads `/actuator/health` (found by running the stack, not by a unit test) | 14.4, 1.14 | low | fixed | `apps/web/src/lib/security/edge.test.ts` (only `/actuator/health` passes; `/actuator`, `/actuator/env`, `/actuator/health/liveness` and dot-segment spellings stay blocked), `apps/web/e2e/security.spec.ts` (health answers 200 with `status` and `groups` only, `/actuator/env` stays 404) |


## Checked and found fine

Each line says how it was checked. *Tested* means an existing or new test pins it; *reasoned* means read from the code.

**Authentication and sessions (ASVS 2, 3)**
- Password hashing: BCrypt with a configurable strength, 12 by default. Tested (`LoginAndAccessTokenTests`).
- JWT: HS256 pinned, `exp` and `iss` validated, secret of at least 32 characters enforced at startup (`@Size(min = 32)` on `AuthProperties`, startup fails without `JWT_SECRET`). Tested.
- Refresh tokens: single use, rotated, reuse of a rotated token revokes the family, logout invalidates. Tested (`identity` suites).
- Email verification and password reset tokens: 256 random bits from `SecureRandom` (`Tokens`), stored hashed, expiring (24 h and 1 h) and single use. Tested.
- Account enumeration: login, reset and signup give the same response for known and unknown addresses. Tested.
- Brute force: per-IP and per-account limits on login and password reset, fail closed when Redis is down. Tested (`RateLimiterUnavailableTests`).
- Google sign-in: issuer and audience checked against the configured client id (`JwksGoogleTokenVerifier`). Reasoned plus existing tests with a stubbed JWKS.

**Access control (ASVS 4)**
- Every endpoint is either on the ownership guard's allow-list or has a registered test that another user cannot read or change the resource. The guard enumerates the Spring handler mappings, so it cannot drift. Tested.
- Admin endpoints require the admin role; `/internal/**` requires the service token. Tested (`AdminAndInternalAccessGuardTests`). The edge never routes `/internal/**` or actuator paths to the browser (`apps/web/src/proxy.ts`, tested).
- No state-changing `GET`: asserted from the handler mappings by the inventory used by the rate-limit and ownership tests.

**Input, injection and deserialisation (ASVS 5)**
- SQL: no native JPA queries; all 155 `JdbcClient` statements use named parameters. The only string concatenation in SQL joins compile-time constants (column lists, a fixed `where s.code = :code` fragment in `SourceAdmin`, a hard-coded table list in `ProfileDeletionHandler`). Reasoned by grep and reading each dynamic site.
- No Java native deserialisation or polymorphic Jackson default typing anywhere in `src/main`. Reasoned by grep.
- DTOs are records with Jakarta Validation; bodies are bounded by the framework limits. Reasoned.
- Untrusted job and CV text is wrapped in delimited blocks in ai-service prompts and outputs are schema-validated (PLAN section 7; existing ai-service tests, 574 passing).

**Files (ASVS 12)**: size cap, extension and declared-type allow-list, magic bytes must agree, sanitised display name never used as a key, no archives, macro-enabled or OLE formats, zip-bomb check, scanner called before storage or parsing. Tested (`UploadSafetyTests`).

**Information exposure (ASVS 7, 14.3)**: error bodies are RFC 7807 with no stack, SQL or class names; actuator is `health` only with no detail; Swagger and the API description are off in `prod`. Tested (`InformationExposureTests`).

**Secrets (ASVS 2.10, 14.1)**: gitleaks over the working tree (6.13 MB) and the full history (452 commits scanned, 6.7 MB) found nothing. The known GitGuardian "password" hit in `extension/src/popup/popup.ts` is a DOM id lookup for the password input, not a secret; it is allow-listed by path in `.gitleaks.toml`, which also excludes generated output (`.next`, `target`, `node_modules`, `.venv`, Playwright output). `.env.example` holds `change-me-*` placeholders only, compose and `application.yml` have no usable default for any secret, Dockerfiles and workflows inline none (`SecretsConfigTests`). The main checkout (`~/Documents/jobfinder`) was not touched or read.

**Dependencies (ASVS 14.2)**: see the raw results below. Application images use pinned tags; the data-store images in compose use tag plus digest.

**Extension (ADR 0035)**: its service-worker fetch is authorised by `host_permissions`, not CORS, so CORS was not widened for `chrome-extension://`. The cookie endpoints accept a configured extension origin only. Tested.

**CI**: `security.yml` runs gitleaks (tree and history), `npm audit --omit=dev --audit-level=high` (blocking), `npm audit` over dev tooling (informational), pip-audit (blocking) and the OSV Maven scan (blocking at HIGH), on every PR and weekly. Dev-tooling advisories are informational because they have no fix that does not downgrade the toolchain and never run on untrusted input; production dependencies block because they ship.

## Raw scan results (run on this branch, 2026-10-06)

| Scan | Command | Result |
|---|---|---|
| Secrets, full history | `gitleaks detect --source . --config .gitleaks.toml` | 452 commits scanned, no leaks found |
| Secrets, working tree | `gitleaks detect --source . --config .gitleaks.toml --no-git` | 6.13 MB scanned, no leaks found |
| npm, production | `npm audit --omit=dev --audit-level=high` | found 0 vulnerabilities (after SEC-17) |
| npm, everything | `npm audit` | 8 high, 0 critical; all in dev tooling (see SEC-R9) |
| npm, extension | `npm audit --omit=dev -w @jobfinder/extension` | found 0 vulnerabilities |
| Python | `uvx pip-audit -r <uv export --frozen --no-dev> --no-deps --disable-pip` | No known vulnerabilities found |
| Maven | `dependency:list` + `infra/security/osv_maven_scan.py` | 287 dependencies queried, no known vulnerabilities (after the jsoup bump) |

`npm audit` and OSV need the network; both were reachable. A Maven scan with OWASP dependency-check was not used; OSV over the resolved dependency list was.

## Residual risks

All are low or informational and none has a fix that fits P6.2; each names where it goes.

| Id | Risk | Severity | Reason it stays open | Where |
|---|---|---|---|---|
| SEC-R1 | No TLS termination or HSTS preload in this repo; HSTS is sent only when the request arrived over TLS | low | Edge is a deployment concern | P6.5 |
| SEC-R2 | web `style-src` keeps `'unsafe-inline'` (React and the UI kit emit `style` attributes); scripts have no `unsafe-inline` | low | Removing it breaks the UI kit; script injection is the dangerous half and is closed | P6.3 |
| SEC-R3 | Access tokens cannot be revoked before their 15 minute expiry | low | Short lifetime; revocation list adds a Redis hit per request | P6.3 |
| SEC-R4 | A DNS answer that changes between the SSRF check and the connect inside a single request is not closed by the JDK client | low | Targets are fixed provider domains plus validated tokens; no redirects followed; the check repeats on every request | accepted |
| SEC-R5 | No log redaction layer: the rule is kept by never logging such values (tested for core-api); ai-service logs and a future proxy log are not covered by a test | informational | Gap list for P6.3 | P6.3 |
| SEC-R6 | Redis has no authentication on the compose network | low | Not published to the host in production; managed Redis with auth is the P6.5 target | P6.5 |
| SEC-R7 | `/internal/**` relies on the service token alone; no network allow-list or mutual TLS | low | Not reachable from the edge (tested); network policy is infrastructure | P6.5 |
| SEC-R8 | Application image base tags are pinned but not digest-pinned (the data-store images are) | low | Digests go stale silently; add with the registry and an update bot | P6.5 |
| SEC-R9 | Eight high npm advisories in dev tooling (`braces`, `micromatch`, `fast-glob`, `ts-morph`, `@ts-morph/common`, `shadcn`, `eslint-config-next`, `@next/eslint-plugin-next`), reached only through `shadcn` and the ESLint config. The only "fix" offered downgrades `shadcn` to 1.0.0 and `eslint-config-next` to 14. None is in the production tree or runs on untrusted input | low | No fix without a downgrade. Time-boxed exception: re-check by 2027-01-06 or when `shadcn` or `eslint-config-next` ship a release that bumps `braces` | CI informational job |
| SEC-R10 | The upload scanner defaults to `type=none` (a no-op) in compose; production must set `UPLOAD_SCANNER_TYPE=clamav` and run a clamd | low | Independent checks (type, magic bytes, macro and archive refusal, size) always apply; with `clamav` the default is fail-closed. The deployment must enable it | P6.5 |
| SEC-R11 | The core-api read-only root filesystem and `cap_drop` in the production overlay were checked from the file only, not by starting that overlay | informational | Needs real SMTP and storage values; see "Verification" in the task summary | P6.5 |
