import { registerOTel } from "@vercel/otel";

import { startSentry } from "./lib/observability/report-error";

const DROP_SPANS = {
  export: (_spans: unknown, done: (result: { code: number }) => void) => done({ code: 0 }),
  shutdown: () => Promise.resolve(),
};

export function registerObservability() {
  // Spans always exist, so the request has a trace id; they leave only when export is on (and OTEL_EXPORTER_OTLP_ENDPOINT
  // says where). Nothing sensitive is attached: @vercel/otel records the method, route and status.
  const exporting = process.env.OTEL_TRACING_EXPORT === "true";
  registerOTel({
    serviceName: process.env.OTEL_SERVICE_NAME ?? "web",
    ...(exporting ? {} : { traceExporter: DROP_SPANS as never }),
  });
  startSentry();
}
