# 0005. Assisted apply by default, auto-submit only where permitted

## Status
Accepted

## Context
Users want to apply quickly. Fully automated submission on third-party sites breaks most
sites' terms, runs into CAPTCHAs and bot detection, and tends to produce many low-quality
applications that hurt the user's reputation with employers.

## Decision
- The default flow is **assisted apply**: JobFinder prepares an application pack (tailored CV,
  cover letter, answers to common screening questions), opens the employer's apply URL, lets the
  user copy answers, and the user submits. The user then confirms "I applied", which creates a
  tracker entry.
- The Phase 5 browser extension autofills forms from the profile and always leaves the final
  submit to the user.
- True auto-submit is only built for channels that explicitly allow it (for example an
  official apply API), each recorded in its own ADR.
- Bypassing CAPTCHAs or bot detection is never done.

## Consequences
- Applications stay high quality and user-owned, and the product avoids ToS violations.
- Applying takes a few more clicks than "one-click apply to 100 jobs"; the product competes on
  match quality and speed of preparation instead.
