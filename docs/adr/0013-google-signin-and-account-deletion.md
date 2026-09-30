# 0013. Google sign-in, account deletion, admin seeding

## Status
Accepted

## Context
P1.2 adds Google login that links to existing email accounts, `DELETE /me` with a deletion event every
module must handle, and a way to create the first admin. PLAN.md §5/§9 fix the tables and the "full data
purge" requirement but not the flow details.

## Decision
- **Google flow**: the web app obtains a Google ID token (Google Identity Services) and posts it to
  `POST /auth/google`. core-api verifies the signature against Google's JWKS, `iss`, expiry and that `aud`
  equals `GOOGLE_CLIENT_ID`. No server-side redirect/code flow, so no client secret is needed and the
  existing "access token in body, refresh token in cookie" contract is unchanged. Blank `GOOGLE_CLIENT_ID`
  makes the endpoint answer 503 `google_not_configured`.
- **Account matching**: by `oauth_accounts(provider, provider_user_id)` (Google `sub`) first, then by email.
  Google's email is trusted only when `email_verified` is true; otherwise 401. A new user is created
  verified, with a null password.
- **Pre-registration takeover**: if the matching account is unverified, someone may have signed up with the
  victim's address and a password they know. Linking therefore verifies the account, **clears its password
  hash and revokes its refresh tokens**. A verified existing account keeps its password and gains Google as a
  second way in.
- **Deletion**: `DELETE /me` publishes `identity.UserDeletionRequested(userId, requestedAt)` inside one
  transaction, then deletes the user row. Handlers are plain synchronous `@EventListener`s so a failing
  module rolls back the whole deletion (all-or-nothing) rather than leaving orphaned data. The event holds no
  PII. Identity's handler purges refresh tokens, email tokens and OAuth links. Later modules (profile,
  documents, ...) must add their own handler in the same PR that introduces their user-keyed tables. The
  user row is hard-deleted (no soft delete), per the "full data purge" requirement. Deletion needs only a
  valid access token (no password re-entry) for now, which also covers Google-only users.
- **Admin seeding**: `ADMIN_EMAIL` (+ optional `ADMIN_PASSWORD`, 10-72 bytes) at startup. An existing account
  is promoted; otherwise a verified ADMIN is created if a password is given. Idempotent, never resets an
  existing password, no defaults.

## Consequences
- Google login cannot be exercised end-to-end without a real client ID; tests replace the verifier.
- Object-storage and vector rows are not touched yet; the modules that own them add handlers later.
