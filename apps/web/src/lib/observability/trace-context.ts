/**
 * W3C trace context for the call to core-api (docs/adr/0039-observability.md). The Next server forwards /api/core/*
 * to core-api (a rewrite), so the trace is carried by one header: the span of the Next request when OpenTelemetry is
 * running, else the caller's trace, else a new one started here, so a browser call is traceable either way.
 */
const TRACEPARENT = /^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/;

export function isValidTraceparent(value: string | null | undefined): value is string {
  if (!value) return false;
  const match = TRACEPARENT.exec(value);
  // An all-zero trace or span id is invalid by the spec.
  return !!match && !/^0+$/.test(match[1]) && !/^0+$/.test(match[2]);
}

function hex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return Array.from(buffer, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function newTraceparent(): string {
  return `00-${hex(16)}-${hex(8)}-01`;
}

export function traceIdOf(traceparent: string): string {
  return traceparent.slice(3, 35);
}

/**
 * The traceparent to forward. `fromActiveSpan` is the context OpenTelemetry would inject (undefined when no span is
 * active); an invalid value from anywhere is ignored, never forwarded.
 */
export function traceparentToForward(
  incoming: string | null | undefined,
  fromActiveSpan?: string,
): string {
  if (isValidTraceparent(fromActiveSpan)) return fromActiveSpan;
  if (isValidTraceparent(incoming)) return incoming;
  return newTraceparent();
}
