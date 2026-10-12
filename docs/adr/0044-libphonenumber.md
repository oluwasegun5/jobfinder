# 0044. libphonenumber for phone numbers in CVs

## Status
Accepted

## Context
The trial gate (ADR 0042, section 5) uses the phone numbers in a parsed CV as a soft signal. To compare them, each number
must first be turned into one canonical form: valid, E.164, default region NG, at most three per CV. CVs write numbers
in many ways (`0803 123 4567`, `+234 (0) 803-123-4567`, `234803...`, foreign numbers with or without a trunk prefix).
Validating a number needs per-country numbering plans, and a regex can't supply those. Hand-written rules would give
false matches, or miss matches, in a fraud signal.

## Decision
Add `com.googlecode.libphonenumber:libphonenumber` (Google, Apache 2.0, version pinned in `pom.xml`). It is used only in
`activation.IdentityNormaliser`:

- `PhoneNumberUtil.findNumbers` with region `NG`, leniency `VALID`.
- `isValidNumber`.
- Formatting as E.164.

It is a single jar with no transitive dependencies. It does no I/O and makes no network calls, because its metadata is
bundled.

## Consequences
- The numbering-plan metadata changes with every release. Dependabot keeps the version current. A new release can turn
  a number that used to be invalid into a valid one, which at worst adds or drops a soft signal.
- Nothing else in the code base may start formatting phone numbers its own way. Callers go through
  `IdentityNormaliser`.
