"""OpenTelemetry: spans for every inbound request and outbound httpx call, trace context in and out.

Spans always exist (so log lines carry a trace id); they leave the process only when
OTEL_TRACING_EXPORT is on. W3C traceparent is the propagation format, as in core-api.
"""

from fastapi import FastAPI
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SimpleSpanProcessor, SpanExporter
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased

from app.config import Settings
from app.observability.logs import SERVICE_NAME

# Probes and scrapes would drown the traces.
_EXCLUDED_URLS = "health,metrics"


def setup_tracing(
    app: FastAPI, settings: Settings, span_exporter: SpanExporter | None = None
) -> TracerProvider:
    provider = TracerProvider(
        resource=Resource.create({"service.name": SERVICE_NAME}),
        sampler=ParentBased(TraceIdRatioBased(settings.tracing_sample_probability)),
    )
    if span_exporter is not None:
        provider.add_span_processor(SimpleSpanProcessor(span_exporter))
    elif settings.otel_tracing_export:
        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=settings.otel_traces_endpoint))
        )
    FastAPIInstrumentor.instrument_app(
        app,
        tracer_provider=provider,
        excluded_urls=_EXCLUDED_URLS,
        # The per-message ASGI spans add nothing to a request span.
        exclude_spans=["receive", "send"],
    )
    httpx_instrumentor = HTTPXClientInstrumentor()
    if httpx_instrumentor.is_instrumented_by_opentelemetry:
        httpx_instrumentor.uninstrument()
    httpx_instrumentor.instrument(tracer_provider=provider)
    return provider
