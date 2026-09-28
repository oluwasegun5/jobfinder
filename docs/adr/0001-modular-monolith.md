# 0001. Modular monolith plus one Python AI service

## Status
Accepted

## Context
JobFinder is built by one developer working with Claude Code. The product has about a dozen
business areas (identity, profile, jobs, ingestion, matching, documents, applications, interview,
notifications, billing, admin). Splitting each into its own service on day one would multiply
deployments, auth between services, distributed tracing, local setup and failure modes before
there is a single user.

The AI work (CV parsing, embeddings, scoring, tailoring, interview prep) is best served by the
Python ecosystem and has a different scaling and cost profile from the CRUD core.

## Decision
- **core-api** is one Spring Boot application (Java 21) organized as a modular monolith with
  Spring Modulith. Code is packaged by module, not by layer; each module exposes a small public
  API and keeps internals in an `internal` subpackage.
- Modules talk only through public module services or Spring application events, never through
  each other's repositories. `ApplicationModules.of(...).verify()` runs in the test suite and
  fails the build on a boundary violation.
- **ai-service** is a separate FastAPI service (Python 3.12) and the only other deployable in
  v1. core-api calls it over REST for interactive requests and RabbitMQ for batch work.
- The web app talks only to core-api. ai-service is internal only.

## Consequences
- One database, one deploy and one debugger session for most features.
- Module boundaries are enforced by tests, so any module can later be extracted into its own
  service when load or team size justifies it.
- Modules share a JVM and a database, so a runaway module can affect the others; this is
  accepted until metrics say otherwise.
- The Java/Python split means two toolchains in CI and locally.
