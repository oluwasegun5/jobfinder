"""Prometheus metrics (PLAN.md section 11: AI cost per feature, latency) and their feeder."""

from prometheus_client import (
    CollectorRegistry,
    Counter,
    Histogram,
)
from prometheus_client.gc_collector import GCCollector
from prometheus_client.platform_collector import PlatformCollector
from prometheus_client.process_collector import ProcessCollector

from app.llm.base import LLMError, LLMProvider, LLMRequest, LLMResponse, LLMUsage

_LATENCY_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 40, 80, 160)


class Metrics:
    """One registry per app, so tests and multiple apps never share state."""

    def __init__(self) -> None:
        self.registry = CollectorRegistry()
        ProcessCollector(registry=self.registry)
        PlatformCollector(registry=self.registry)
        GCCollector(registry=self.registry)
        self.llm_calls = Counter(
            "ai_llm_calls_total",
            "Provider calls",
            ["feature", "provider", "model", "outcome"],
            registry=self.registry,
        )
        self.llm_cost = Counter(
            "ai_llm_cost_usd_total",
            "Provider cost in US dollars, from the pinned price list",
            ["feature", "model"],
            registry=self.registry,
        )
        self.llm_tokens = Counter(
            "ai_llm_tokens_total",
            "Tokens used",
            ["feature", "model", "direction"],
            registry=self.registry,
        )
        self.llm_duration = Histogram(
            "ai_llm_call_duration_seconds",
            "Provider call latency",
            ["feature"],
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self.http_duration = Histogram(
            "ai_http_request_duration_seconds",
            "Inbound request latency by route template",
            ["method", "route", "status"],
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )

    def record_usage(self, usage: LLMUsage, outcome: str) -> None:
        self.llm_calls.labels(usage.feature, usage.provider, usage.model, outcome).inc()
        self.llm_cost.labels(usage.feature, usage.model).inc(float(usage.cost_usd))
        self.llm_tokens.labels(usage.feature, usage.model, "input").inc(usage.input_tokens)
        self.llm_tokens.labels(usage.feature, usage.model, "output").inc(usage.output_tokens)
        self.llm_duration.labels(usage.feature).observe(usage.latency_ms / 1000)


class ObservedProvider:
    """Counts every provider call, failed billed ones included, and passes the result through."""

    def __init__(self, inner: LLMProvider, metrics: Metrics) -> None:
        self._inner = inner
        self._metrics = metrics
        self.name = inner.name

    async def generate(self, request: LLMRequest) -> LLMResponse:
        try:
            response = await self._inner.generate(request)
        except LLMError as error:
            if error.usage:
                for usage in error.usage:
                    self._metrics.record_usage(usage, "error")
            else:
                self._metrics.llm_calls.labels(request.feature, self.name, "unknown", "error").inc()
            raise
        self._metrics.record_usage(response.usage, "ok")
        return response

    async def aclose(self) -> None:
        await self._inner.aclose()
