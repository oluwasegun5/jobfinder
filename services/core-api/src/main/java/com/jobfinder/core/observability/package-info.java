/**
 * Observability plumbing (docs/adr/0039-observability.md): the PII redaction every log line and error report passes
 * through ({@link com.jobfinder.core.observability.PiiRedactor}), the error reporter ({@link
 * com.jobfinder.core.observability.ErrorReporting}, Sentry when a DSN is set) and {@link
 * com.jobfinder.core.observability.CachedGauge} for gauges that read the database. The trace id on every response,
 * Sentry start-up and the log-redaction hook live in {@code internal}. Depends on no other module.
 */
package com.jobfinder.core.observability;
