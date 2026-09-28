# 0007. Provider-agnostic LLM layer

## Status
Accepted

## Context
LLM providers, models and prices change quickly. If model calls were spread across services and
business code, swapping a model, comparing providers or tracking cost would mean touching many
places.

## Decision
- All LLM and embedding calls go through ai-service; core-api and the web app never call a
  provider directly.
- Inside ai-service, every call goes through the `app/llm/` provider interface. Implementations
  (Anthropic first) sit behind it, and a fake provider is used in tests.
- Business code asks for a model tier (for example fast vs strong), and tiers map to model names
  from configuration, never from code.
- Prompts are versioned files (`app/prompts/<feature>/v<n>.md`); changing a prompt adds a new
  version.
- Every output is validated against a Pydantic model, with one retry on validation failure and
  then a loud failure.
- Providers and settings are chosen so submitted data is not used for training.

## Consequences
- Changing a model or provider is a config change plus an eval run, not a code change.
- One place to add caching, rate limiting, cost accounting and tracing.
- The abstraction has to stay small; provider-specific features are only used when they can be
  expressed through the interface.
- See ADR 0010 for the concrete ai-service conventions.
