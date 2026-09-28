# 0004. No LinkedIn or Indeed scraping or automated login

## Status
Accepted

## Context
LinkedIn and Indeed hold many listings users care about. Both prohibit scraping and automated
access in their terms, actively detect it, and ban accounts. Automating a user's logged-in
session would put the user's own account at risk.

## Decision
- JobFinder never scrapes LinkedIn or Indeed and never automates logins to them.
- Their listings are reached indirectly through aggregator APIs that are licensed to index them
  (for example JSearch / Google for Jobs), following those APIs' terms.
- Users can still add jobs found on those sites manually to the application tracker.

## Consequences
- No legal or account-ban exposure from the two largest job sites.
- Some listings arrive later or not at all compared with scraping them directly.
- This rule is not reopened for convenience; changing it needs a new ADR backed by a licensed
  data agreement.
