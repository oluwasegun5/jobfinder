# 0012. Identity: email/password auth design

## Status
Accepted

## Context
P1.1 implements signup, login, logout, refresh, email verification and password reset. PLAN.md §5/§9 fix
the essentials (15-min JWT access tokens, rotating httpOnly refresh tokens with reuse detection, Redis rate
limits, `users` / `refresh_tokens` tables) but leave several details open.

## Decision
- **Access tokens**: HS256 JWTs signed with `JWT_SECRET` (≥ 32 chars, startup fails without it), claims
  `sub`=user id, `role`, `iss`, `jti`. Verified by Spring Security's resource server with zero clock skew.
  Not revocable within their 15 minutes; logout and password reset revoke refresh tokens, which is what
  bounds a stolen access token to its remaining lifetime.
- **Refresh tokens**: opaque 256-bit random values, stored as SHA-256 hashes. Each login starts a *family*;
  each refresh atomically revokes the presented token (`UPDATE ... WHERE revoked_at IS NULL`) and issues a
  successor. Presenting an already-revoked token revokes the whole family (reuse attack) — the legitimate
  holder must log in again. Sent only as a cookie: `HttpOnly; Secure; SameSite=Strict`, path
  `/api/core/auth` (the browser-visible path behind the web proxy, ADR 0011), 30-day lifetime.
  Redis is not used for a denylist: revocation lives in the database, which is already authoritative.
- **CSRF**: disabled. API calls use a bearer header; the two cookie-authenticated endpoints (refresh,
  logout) rely on `SameSite=Strict`.
- **Email tokens** (verify, reset): a third table, `email_tokens` (hash, type, expiry, `used_at`), because
  reset links must be single-use. Verification links last 24 h, reset links 1 h; issuing a new token voids
  earlier unused ones of the same type. `oauth_accounts` is left to P1.2.
- **Email verification gates login**: unverified users get 403 `email_not_verified` (only after the
  password is proven correct). `POST /auth/resend-verification` exists so an expired link is not a dead end.
  A completed password reset also marks the address verified (the user proved mailbox control).
- **No account enumeration**: signup, resend-verification and forgot-password always answer 202. Signup for an
  existing address emails the owner instead of creating anything. Login returns one error for unknown email and
  wrong password and hashes against a dummy value to equalise timing. Emails are sent asynchronously after
  commit so SMTP latency cannot leak existence either.
- **Password hashing**: BCrypt (cost 12, configurable) rather than Argon2, to avoid an extra native/crypto
  dependency for v1; passwords are 10–72 bytes (BCrypt's limit).
- **Rate limiting**: bucket4j on Redis, checked in the service layer so limits can key on IP *and* on email
  (hashed in Redis keys): login 20/15 min per IP and 10/15 min per email, signup 5/h per IP, forgot-password and
  resend 5/h per IP and 3/h per email, refresh 60/min per IP, verify/reset 10/h per IP. Overridable under
  `app.rate-limit.rules.*`. **Fails closed**: if Redis is unreachable auth endpoints return 503 rather than run
  unthrottled. The Redis connection is opened lazily so the app still starts and reports health.
- **Client IP** is the socket peer address. Behind a reverse proxy, enable Spring's forwarded-header handling for
  the trusted proxy (`server.forward-headers-strategy`) or every user shares the proxy's bucket. Not configured
  yet because no production topology exists.
- **Module layout**: `SecurityConfig` moved from `shared` into `identity.internal` (authentication is identity's
  concern). Other modules learn who is calling through `identity.CurrentUser.require()`. `shared` gained
  `ApiException` (status + stable `code`) and the global handler now extends `ResponseEntityExceptionHandler`
  so validation errors are RFC 7807 400s instead of falling into the generic 500.
- **Email column** is `citext` per PLAN.md §5; the service lower-cases addresses before every lookup or save.
- `/actuator/health`, `/actuator/info`, `/v3/api-docs` and Swagger UI stay public for now (see ADR 0011);
  everything else requires a token. The mail health indicator is disabled so a mail outage cannot mark
  core-api unhealthy.

## Consequences
- `JWT_SECRET` is a new required setting (added to `.env.example` and compose).
- Expired refresh/email token rows are not yet purged; a cleanup job is needed before production.
- Rotating `JWT_SECRET` logs everyone's access tokens out (refresh tokens survive and mint new ones).
- A legitimate client that fires two refreshes with the same token concurrently trips reuse detection and is
  logged out; the web client (P1.3) must serialise refreshes.
