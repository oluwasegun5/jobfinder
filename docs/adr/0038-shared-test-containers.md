# 0038. One set of test containers per JVM, isolated per Spring context

## Status
Accepted

## Context
Each core-api integration test context imported `TestcontainersConfiguration`, whose container `@Bean`s gave every
Spring test context its own Postgres (pgvector), Redis, RabbitMQ, Mailpit and S3Mock. Spring caches contexts (default
up to 32), so containers piled up as the suite moved through contexts with different configuration. By P6.2 the suite
had about 20 distinct contexts, and a full run kept 90 to 100 containers running in a Docker Desktop VM of 7.6 GiB.
The VM ran out of memory and killed containers (exit 137). Contexts then failed to load ("Connection to
localhost:NNNNN refused", Hikari timeouts, `ContainerLaunchException`), and which classes failed changed from run to
run: 111 errors in one full run, 40 in the next, every one of those classes green on its own. The full suite also went
from 11.5 minutes (1469 tests, 4 October) to about 50 minutes, mostly spent starting containers (96 s per context on
average).

A context needs state of its own, not containers of its own. Each context runs its own listeners and schedulers against
whatever it is connected to: a RabbitMQ consumer of a cached context would take messages another context published and
call that context's own WireMock ai-service; a scheduler would act on rows another context's test created. So the
isolation the tests silently relied on (a fresh database, broker and rate-limit store per context) has to stay.

## Decision
- **One set of containers per JVM.** `TestcontainersConfiguration.Shared` holds the five containers as static fields and
  starts them together the first time a context needs them. They are not beans, so closing or evicting a context never
  stops them. Ryuk removes them when the JVM exits.
- **State per context on the shared containers.** A `ContextResources` bean, created before any other bean of the
  context and so destroyed after them, gives each context:
  - its own database (`create database ctx_N`; Flyway migrates it as before; `drop database ... with (force)` on close);
  - its own RabbitMQ virtual host (`rabbitmqctl add_vhost` and permissions; `delete_vhost` on close);
  - its own Redis database for the rate limiter (Redis runs with `--databases 64`; an index is taken from a pool,
    flushed, and returned on close).

  Connection settings go through the existing `DynamicPropertyRegistrar` instead of `@ServiceConnection`.
- **Shared on purpose:** Mailpit (tests already look mail up by recipient) and the S3 bucket (objects are stored under
  random keys). Tests autowire a `Mailpit` value (its API base URL) instead of the container.
- **Context cache capped at 12** (`src/test/resources/spring.properties`, `spring.test.context.cache.maxSize`). Every
  live context keeps a Hikari pool (10) and broker connections open on the shared containers. Twelve contexts stay well
  under the Postgres `max_connections=200` and the 64 Redis databases, and keep the JVM heap bounded. An evicted
  context is closed and reloaded when needed; with the containers already running, a reload costs only Spring startup.
- Production code and production defaults are unchanged. No test was skipped, disabled or relaxed.

## Consequences
- A full run keeps 6 test containers (the 5 shared ones and Ryuk), whatever the number of contexts. The full suite
  passed twice in a row (1593 tests, 0 failures, 0 errors) in 14:33 and 13:20 minutes; before this change no full run
  on this machine had been green since P6.2 began.
- Isolation per context is now explicit, in one place, rather than a side effect of starting new containers.
- A context that is set up wrong now fails on a shared container instead of its own. A crashed shared container fails
  every later context in that JVM; the failures say so instead of failing at random.
- `TestCoreApiApplication` (the local dev run) uses the same configuration and gets one context on the shared
  containers.
- If more than 12 contexts ever need to be alive at once, raise the cache cap together with `POSTGRES_MAX_CONNECTIONS`
  (and keep it below the Redis database count).
