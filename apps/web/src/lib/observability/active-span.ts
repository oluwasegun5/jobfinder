import { context, propagation } from "@opentelemetry/api";

/**
 * The traceparent of the span OpenTelemetry has open for this request, or undefined when it is not running (the API is
 * then a no-op), so the caller falls back to the incoming or a new trace.
 */
export function activeTraceparent(): string | undefined {
  const carrier: Record<string, string> = {};
  propagation.inject(context.active(), carrier);
  return carrier.traceparent;
}
