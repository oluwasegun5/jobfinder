# 0037. Security hardening against OWASP ASVS L2

## Status
Accepted

## Context
P6.2 (`PLAN.md` section 7 security and cost control, section 14 risks, Phase 6). The whole system was reviewed against
OWASP ASVS level 2 (core-api, ai-service, web, extension, compose and Docker, CI), findings were fixed in code with a
test that fails before and passes after, and the review is written up in `docs/security-review.md` (findings table,
what was checked and found fine, residual risks). This record keeps the decisions that shape the code. No migration was
added (the last one is still `V32`).

## Decision

### Headers (ASVS 14.4, 14.5)
- **core-api** sends `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` plus CSP `frame-ancestors 'none'` (and
  `default-src 'none'` for the API, except on the Swagger UI), `Referrer-Policy: no-referrer`, a closed
  `Permissions-Policy`, `Cross-Origin-Resource-Policy: same-site` and Spring Security's `Cache-Control: no-store` on every
  response. HSTS (`max-age` 365 days, `includeSubDomains`, both under `app.security.hsts`) is sent only on requests that
  arrived over TLS, so local http is unaffected. One helper, `SecurityHeaderDefaults`, serves both security filter
  chains (public API and `/internal/**`).
- **web** gets a per-request nonce CSP from `src/proxy.ts` (Next 16's replacement for middleware): `script-src 'self'
  'nonce-…' 'strict-dynamic'`, no `unsafe-inline` or `unsafe-eval` in production, `object-src 'none'`,
  `base-uri 'none'`, `frame-ancestors 'none'`, `connect-src 'self'` (the browser only ever talks to the Next origin;
  `/api/core` is a same-origin rewrite), plus nosniff, `Referrer-Policy`, `X-Frame-Options`, `Permissions-Policy` and
  COOP. Nonces need per-request rendering, so the root layout awaits `connection()`. `style-src` keeps
  `'unsafe-inline'` because React and the UI kit set `style` attributes; this is recorded as a residual risk (SEC-R2).

### CORS (ASVS 14.5.3)
An exact origin allow-list (`app.security.cors.allowed-origins`, default the configured `WEB_BASE_URL`), no wildcard
(startup fails on `*` or a path), `Allow-Credentials` off by default, `Vary: Origin`, preflight limited to the methods
and headers the web client uses, a 10 minute cache. A `null` origin and any unlisted origin get no CORS headers. The
Chrome extension's `chrome-extension://` origin is **not** added: its service-worker `fetch` goes through
`host_permissions`, which bypasses CORS (ADR 0035), so widening CORS for it would only widen the attack surface.

### CSRF (ASVS 4.2.2, 13.2.3)
`SameSite=Strict` on the httpOnly refresh cookie stays the primary control. `CrossSiteRequestFilter` adds defence in
depth for the state-changing requests that carry that cookie (`/auth/refresh`, `/auth/logout`): `Origin` present means
it must be a trusted web origin or a configured extension origin (`null` is refused); otherwise `Sec-Fetch-Site` of
`same-origin` or `none` passes and `same-site` or `cross-site` is refused; with neither header the request is a
non-browser client (a browser sends one of them on every cross-site POST) and passes, which is the documented rule.
Every other endpoint takes a bearer header a foreign page cannot attach. Webhooks are signature-authenticated and
exempt. No state-changing `GET` exists (checked from the handler mappings by the inventory test). A refusal is
`403` `cross_site_request_blocked`.

### Rate-limit classes (ASVS 11.1.4, 2.2.1)
One mechanism: the existing identity `RateLimits` (bucket4j on Redis), now driven by `EndpointRateLimitInterceptor`.
`EndpointClassifier` puts **every** handler mapping into exactly one class, and a test fails for any mapping it cannot
place, so a new endpoint cannot ship unclassified (the inventory is generated from the handler mappings and written to
`target/rate-limit-inventory.md`). A refusal is RFC 7807 `429` with `Retry-After`. Keys: the user id when signed in,
the client IP otherwise. Defaults (capacity per period; overridable under `app.rate-limit.endpoints.<CLASS>`):

| Class | Default | Covers | On Redis outage |
|---|---|---|---|
| AI | 30 / 10 min | tailor, cover letter, screening answers, application pack, interview prep, mock turns, match, reparse, follow-up | closed |
| UPLOAD | 20 / 1 h | CV upload | closed |
| DOWNLOAD | 60 / 10 min | CV link, rendered document download | closed |
| SEARCH | 120 / 1 min | feed, job search, similar jobs | closed |
| EXPORT | 20 / 10 min | PDF and DOCX rendering | closed |
| EXTENSION | 120 / 1 min | apply-context | closed |
| ADMIN_ACTION | 20 / 1 min | manual source runs, new targets | closed |
| PUBLIC_LINK | 30 / 1 h per IP | email unsubscribe | closed |
| API_READ | 600 / 1 min | every other read | open |
| API_WRITE | 120 / 1 min | every other write | open |
| EXEMPT | none | `/auth/**` (own per-IP and per-account rules, `RateLimitRule`), checkout and cancel (limited in billing), webhooks, `/internal/**`, actuator | n/a |

Brute force on login and password reset keeps its per-IP and per-account rules and its identical responses for known
and unknown addresses. The test suite relaxes the ceilings through `src/test/resources/application.properties`
(`app.rate-limit.endpoints.*`); production defaults are untouched, and the tests that prove a limit use a small ceiling of
their own.

### Uploads and the scanner port (ASVS 12.1 to 12.5)
`UploadScanner` is a port in `storage`; `UploadScans.requireClean` runs on every upload before anything is stored or
parsed, after the independent checks. Adapters: `NoOpUploadScanner` (default, `app.upload.scanner.type=none`) and
`ClamAvUploadScanner` (clamd `INSTREAM` over TCP, `type=clamav`, host, port and timeouts in config). `on-error` is
`closed` by default everywhere (the only value the production profile accepts silently): a scanner that cannot answer
refuses the upload with a typed error; `open` logs and accepts and must be chosen knowingly. An infected file is
refused with its own code. Independent of the scanner: size limit, extension and declared type allow-list, magic-byte
sniffing that must agree with the declared type, sanitised display name (never a storage path), no archive,
macro-enabled or OLE formats (a DOCX with `vbaProject.bin`, ActiveX or an embedded package is refused) and a zip-bomb
check on the central directory (uncompressed total and expansion ratio). Stored objects are served only through
pre-signed URLs capped at 15 minutes, with `Content-Disposition: attachment`, the stored content type and
`nosniff`/`no-store` set through response overrides.

### Ownership guard (ASVS 4.2.1, 4.1.3)
`EndpointOwnershipGuardTests` enumerates every handler mapping (`EndpointInventory`) and fails when an endpoint is
neither on an explicit allow-list (public or ownerless, one-line reason each) nor registered by `@CoversEndpoints` on a
test that proves another user cannot read or change the resource (404 or 403 as the module convention says) or, for a
per-user collection or setting, sees only their own. `AdminAndInternalAccessGuardTests` proves the admin endpoints
need the admin role and `/internal/**` needs the service token. Writing the guard exposed nine endpoints without a
registered test; all nine are caller-scoped by construction (no foreign resource id) and got tests, no IDOR was found.

### Authentication
Kept as reviewed: BCrypt strength 12, HS256 pinned with a secret of at least 32 characters enforced at startup,
`exp` and `iss` validated with no leeway, single-use rotating refresh tokens with reuse detection and atomic
revocation, hashed one-time email tokens of 256 random bits, Google `aud` and `iss` checks. Changed: signup hashes the
password before looking the address up (constant work, no timing enumeration), and access tokens now carry and are
required to carry `aud: jobfinder-api` (`app.auth.jwt.audience`).

### SSRF (ASVS 12.6.1, 5.2.6)
`shared.SsrfGuard` is called before every request of the ingestion clients (`AtsHttp`, `AggregatorHttp`): https only,
default port only, no user info, and every address the host resolves to must be public (loopback, link-local including
the metadata address, private, carrier-grade NAT, unique-local, multicast, reserved, and IPv4 inside IPv6 mapped, NAT64
or 6to4 forms; numeric spellings are caught because the host is always resolved). The clients do not follow redirects,
so a redirect cannot lead past the check, and the check runs again for every request, which defeats a DNS answer that
changes between requests. A name that changes its answer between the check and the connect inside one request is not
closed by the JDK client; the residual is accepted because every target is a fixed provider domain plus a
validated token (SEC-R4). The relaxed policy used by the test suite (`app.security.ssrf.*`, localhost over http) is
never set in a deployed profile.

### Information exposure (ASVS 7.4, 14.3)
`application-prod.yml`: Swagger UI and the API description off, actuator `health` only with no detail, no error message,
binding errors or stack trace in error bodies. The base profile also exposes `health` only (`/actuator/info` is gone).
The OpenAPI description stays available in development because the web client is generated from it.

### Secrets, dependencies, infrastructure
- No secret has a usable default in `application.yml`, `application-prod.yml`, the compose files, Dockerfiles or CI
  (`SecretsConfigTests` fails on one). `POSTGRES_PASSWORD`, `RABBITMQ_PASSWORD` and the object-storage keys are now
  required like `JWT_SECRET` and `AI_SERVICE_TOKEN`; `.env.example` holds `change-me-*` placeholders and `make env`
  writes a `.env` with generated values. Running core-api from an IDE needs those variables set.
- gitleaks (`.gitleaks.toml`, default rules, the build-output paths and the `popup.ts` DOM-id false positive allowed)
  runs over the working tree and full history in CI.
- Dependencies: `.github/workflows/security.yml` runs gitleaks, `npm audit --omit=dev --audit-level=high` (blocking),
  `npm audit` for development tooling (informational: it reports advisories with no fixed version that never reach a
  running service), pip-audit over the locked ai-service dependencies (blocking) and an OSV scan of the resolved Maven
  dependencies (blocking at HIGH). It also runs weekly. Fixes shipped: Boot-managed Tomcat, Jackson 2 and 3 and the
  RabbitMQ client are overridden in `pom.xml` to the fixed releases (remove each override when the Boot BOM catches up),
  jsoup is upgraded, and `shadcn` (a build-time CSS and CLI package) moved to `devDependencies`.
- `infra/docker-compose.prod.yml`: only web publishes a port, core-api runs with the prod profile, all capabilities
  dropped, `no-new-privileges`, read-only root filesystems where verified (web and ai-service were started read-only;
  core-api is configured the same way but not started, see the review), restart policies, pid and memory limits as
  overridable placeholders, development helpers (Mailpit, S3 mock) moved behind a profile. The core-api image now runs as
  a non-root user. Development ports are bound to loopback.

## Consequences
- A new endpoint fails the build until it has a rate-limit class (automatic) and an ownership test or allow-list entry
  (a deliberate act). That is the point, and it costs one annotation or one line.
- Production needs real values for the database, broker and storage secrets, and an SMTP and object-storage endpoint in
  the prod overlay; the stack no longer starts on shared default passwords.
- The web app renders every page per request (nonce CSP), which gives up static prerendering of the auth pages. The pages
  are small and behind the same Node server, so the cost is a little server time.
- Left for later and recorded in the review: TLS termination and HSTS preload, Redis authentication, digest pinning of the
  Dockerfile base images (the registry was unreachable here), an IP allow-list or mutual TLS for `/internal/**`, log
  redaction as a layer instead of by discipline, access-token revocation before the 15 minute expiry, and SRI or a
  stricter `style-src` for the web app (P6.3 and P6.5).
