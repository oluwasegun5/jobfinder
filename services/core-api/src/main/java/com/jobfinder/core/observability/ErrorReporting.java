package com.jobfinder.core.observability;

import org.slf4j.MDC;

import io.sentry.Sentry;

/**
 * Reports an unexpected failure to Sentry. A no-op unless Sentry was started (SENTRY_DSN set), so callers never check.
 * Only the exception goes out, scrubbed by the same rules as the logs (see {@code SentryScrubber}); never request data.
 */
public final class ErrorReporting {

    private ErrorReporting() {
    }

    public static void capture(Throwable error) {
        if (Sentry.isEnabled()) {
            // The trace id (logging puts it in the MDC) ties the Sentry issue to the trace and the log lines.
            String traceId = MDC.get("traceId");
            Sentry.withScope(scope -> {
                if (traceId != null) {
                    scope.setTag("trace_id", traceId);
                }
                Sentry.captureException(error);
            });
        }
    }
}
