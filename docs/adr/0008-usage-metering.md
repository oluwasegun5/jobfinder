# 0008. Usage metering from day one

## Status
Accepted

## Context
LLM calls are the product's main variable cost. Pricing (free tier plus paid plans with AI
credits), cost caps and the admin cost dashboard all depend on knowing what each call cost and
who it was for. Retrofitting metering later means lost history and untrustworthy numbers.

## Decision
- Every AI call records user_id, feature, provider, model, input/output tokens, cost, latency and
  prompt_version. Retries are recorded too, since they are billed.
- ai-service computes cost from configured pricing and returns the usage records to core-api
  with each response.
- core-api writes them to an append-only credit ledger per user (Phase 3) and enforces credit
  balances and per-user caps before starting expensive work.
- Admin sees AI cost per day, per feature and per active user.

## Consequences
- Every AI endpoint, including internal and batch ones, must carry a user or system identity and
  return usage records; this is part of the definition of done for AI features.
- Cost figures depend on the pricing table in config being kept up to date.
- Billing (Phase 6) can build on real historical usage rather than estimates.
