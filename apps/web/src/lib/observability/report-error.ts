import * as Sentry from "@sentry/node";

import { redact } from "./redact";

/** What reaches Sentry: the error type and a redacted message and stack. Nothing of the request. */
export function scrubSentryEvent<T extends Sentry.ErrorEvent>(event: T): T {
  delete event.request;
  delete event.user;
  delete event.server_name;
  delete event.breadcrumbs;
  delete event.extra;
  if (event.message) event.message = redact(event.message);
  for (const exception of event.exception?.values ?? []) {
    if (exception.value) exception.value = redact(exception.value);
    for (const frame of exception.stacktrace?.frames ?? []) delete frame.vars;
  }
  return event;
}

export function startSentry(env: Record<string, string | undefined> = process.env): boolean {
  const dsn = env.SENTRY_DSN;
  if (!dsn) return false;
  Sentry.init({
    dsn,
    environment: env.SENTRY_ENVIRONMENT ?? "local",
    // Nothing of the request or the user goes out; the events are errors only.
    dataCollection: {
      userInfo: false,
      cookies: false,
      httpHeaders: false,
      httpBodies: [],
      urlQueryParams: false,
    },
    // Tracing is OpenTelemetry's job; Sentry is for errors only.
    tracesSampleRate: 0,
    beforeSend: scrubSentryEvent,
  });
  return true;
}

export interface RequestErrorInfo {
  path: string;
  method: string;
  routePath: string;
  routeType: string;
  traceId?: string;
}

/** One redacted JSON line on stdout, and a Sentry event when Sentry is on. */
export function reportRequestError(
  error: unknown,
  info: RequestErrorInfo,
  write: (line: string) => void = (line) => process.stdout.write(`${line}\n`),
): void {
  const message = error instanceof Error ? error.message : String(error);
  const digest =
    typeof error === "object" && error !== null && "digest" in error ? String((error as { digest: unknown }).digest) : undefined;
  write(
    JSON.stringify({
      "@timestamp": new Date().toISOString(),
      "log.level": "ERROR",
      "service.name": "web",
      message: redact(`Unhandled ${info.routeType} error: ${message}`),
      // The path without its query: a query can carry an email or a token.
      path: redact(info.path.split("?")[0]),
      method: info.method,
      route: info.routePath,
      digest,
      traceId: info.traceId,
      "error.stack_trace": error instanceof Error && error.stack ? redact(error.stack) : undefined,
    }),
  );
  if (Sentry.isInitialized()) {
    Sentry.withScope((scope) => {
      if (info.traceId) scope.setTag("trace_id", info.traceId);
      Sentry.captureException(error);
    });
  }
}
