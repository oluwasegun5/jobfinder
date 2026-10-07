# Load test report (P6.5 rehearsal)

## Scope and honesty
- Tool: k6 (`infra/deploy/loadtest/`, run with `run.sh`), executed inside the caddy container's network namespace against the production-shaped local rehearsal stack (`jobfinder-p65`, own TLS via `tls internal`). Users are created by `users.py` with consent already recorded; the run does a login per virtual user, then reads and writes through the public edge.
- Hardware: a heavily loaded developer Mac running every service in Docker. This is a smoke-level load test, not a capacity test. Numbers say the stack is healthy at the load below; they do not predict production capacity.
- PLAN.md names no "target concurrency". The Phase 6 gate "load test at target concurrency passes" therefore cannot be called met. The owner must set the target (expected concurrent users at launch); the script takes it as `VUS`, `RAMP`, `HOLD`.

## Run
10 virtual users, 30 s ramp, 90 s hold, 10 s ramp-down.

| Metric | Result | Threshold |
|---|---|---|
| Requests | 1958 (14 per second) | n/a |
| Failed HTTP requests | 0 % | < 1 % |
| API error counter | 0 | < 1 % |
| Checks passed | 1953 of 1953 (100 %) | > 99 % |
| Latency p95 | 95 ms | < 800 ms |
| Latency p99 | 204 ms | < 2000 ms |
| Latency max | 2.06 s (cold start outlier) | n/a |

All thresholds passed.

## Findings during the run
- The signup rate limit (5 per hour per IP) blocks creating test users through the edge; the rehearsal clears `rl:*` keys in its own Redis only. Never do that in production.
- The login CSRF/Origin check compares against `WEB_BASE_URL`; the script sends a matching `Origin`.
- Spring Boot 4.1 does not honour `management.otlp.tracing.export.enabled`; with the prod profile and no collector, span-export errors were logged until the overlay set `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED`. Fix in `application.yml` belongs to a later P6.3 follow-up.

## Not tested
- AI endpoints against a real provider (cost; no real keys allowed here).
- Concurrency above 10 users, long soak, and failover.
- Run it again against staging with the real target and provider stubs, and record the result here.
