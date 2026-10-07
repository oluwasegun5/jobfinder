import type { Instrumentation } from "next";

/**
 * Observability for the Next server (docs/adr/0039-observability.md): OpenTelemetry spans for every request, exported
 * only when OTEL_TRACING_EXPORT=true, and Sentry for server errors when SENTRY_DSN is set. Node runtime only.
 */
export async function register() {
  if (process.env.NEXT_RUNTIME !== "nodejs") return;
  const { registerObservability } = await import("./instrumentation.node");
  registerObservability();
}

export const onRequestError: Instrumentation.onRequestError = async (error, request, context) => {
  if (process.env.NEXT_RUNTIME !== "nodejs") return;
  const { reportRequestError } = await import("./lib/observability/report-error");
  const traceparent = request.headers["traceparent"];
  const value = Array.isArray(traceparent) ? traceparent[0] : traceparent;
  const { isValidTraceparent, traceIdOf } = await import("./lib/observability/trace-context");
  reportRequestError(error, {
    path: request.path,
    method: request.method,
    routePath: context.routePath,
    routeType: context.routeType,
    traceId: isValidTraceparent(value) ? traceIdOf(value) : undefined,
  });
};
