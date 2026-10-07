import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Request, Response
from opentelemetry.sdk.trace.export import SpanExporter

from app.api import (
    diagnostics,
    health,
    interview,
    mock_interview,
    parse_resume,
    score_matches,
    tailor_resume,
    writing,
)
from app.api import (
    metrics as metrics_api,
)
from app.api.errors import register_error_handlers
from app.config import EmbeddingProviderName, Settings, get_settings
from app.embeddings import EmbeddingProvider, build_embedding_provider
from app.embeddings.batching import BatchCollector
from app.embeddings.core_client import CoreApiClient, EmbeddingKind
from app.embeddings.worker import EmbeddingWorker
from app.llm import LLMProvider, build_provider
from app.observability.logs import configure_logging
from app.observability.metrics import Metrics, ObservedProvider
from app.observability.sentry import start_sentry
from app.observability.tracing import setup_tracing
from app.security import require_service_token
from app.workers.consumer import (
    NOOP_QUEUE,
    AmqpParams,
    ConsumerState,
    MessageHandler,
    RabbitConsumer,
    handle_noop,
)

logger = logging.getLogger(__name__)


def create_app(
    settings: Settings | None = None,
    *,
    provider: LLMProvider | None = None,
    embedding_provider: EmbeddingProvider | None = None,
    core_client: CoreApiClient | None = None,
    span_exporter: SpanExporter | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings)
    start_sentry(settings)
    # Without a key the embed queues are left alone: their messages wait in the broker until the
    # key is set, instead of being rejected into the dead-letter queue one by one.
    embeddings_ready = (
        embedding_provider is not None
        or settings.embedding_provider is not EmbeddingProviderName.VOYAGE
        or settings.voyage_api_key is not None
    )
    if not embeddings_ready:
        logger.warning("VOYAGE_API_KEY is not set: the embedding queues are not consumed")

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        consumer: RabbitConsumer | None = None
        collectors: list[BatchCollector[Any]] = []
        if settings.rabbitmq_enabled:
            handlers: dict[str, MessageHandler] = {NOOP_QUEUE: handle_noop}
            dead_letters: dict[str, str] = {}
            if embeddings_ready:
                worker = EmbeddingWorker(
                    settings, app.state.embedding_provider, app.state.core_client
                )
                for kind, queue, dlq in (
                    (EmbeddingKind.JOB, settings.jobs_embed_queue, settings.jobs_embed_dlq),
                    (
                        EmbeddingKind.RESUME_VERSION,
                        settings.resumes_embed_queue,
                        settings.resumes_embed_dlq,
                    ),
                ):
                    collector: BatchCollector[Any] = BatchCollector(
                        worker.handler(kind),
                        batch_size=settings.embedding_batch_size,
                        max_wait_seconds=settings.embedding_batch_wait_seconds,
                    )
                    collectors.append(collector)
                    handlers[queue] = collector
                    dead_letters[queue] = dlq
            consumer = RabbitConsumer(
                AmqpParams(
                    host=settings.rabbitmq_host,
                    port=settings.rabbitmq_port,
                    login=settings.rabbitmq_user,
                    password=settings.rabbitmq_password.get_secret_value(),
                    virtualhost=settings.rabbitmq_vhost,
                ),
                # A batch fills from unacked deliveries, so the window must hold at least one batch.
                prefetch=max(settings.rabbitmq_prefetch, settings.embedding_batch_size * 2),
                reconnect_seconds=settings.rabbitmq_reconnect_seconds,
                handlers=handlers,
                dead_letter_queues=dead_letters,
            )
            consumer.start()
            app.state.consumer_state = lambda: consumer.state
        else:
            app.state.consumer_state = lambda: ConsumerState.DISABLED
        try:
            yield
        finally:
            if consumer is not None:
                await consumer.stop()
            for collector in collectors:
                await collector.aclose()
            await app.state.llm_provider.aclose()
            await app.state.embedding_provider.aclose()
            await app.state.core_client.aclose()

    # Internal-only service: every route, /health included, requires the service token,
    # and the OpenAPI/docs routes are not exposed.
    app = FastAPI(
        title="JobFinder AI service",
        lifespan=lifespan,
        dependencies=[Depends(require_service_token)],
        openapi_url=None,
        docs_url=None,
        redoc_url=None,
    )
    app.state.settings = settings
    metrics = Metrics()
    app.state.metrics = metrics
    app.state.llm_provider = ObservedProvider(provider or build_provider(settings), metrics)
    app.state.embedding_provider = embedding_provider or build_embedding_provider(settings)
    app.state.core_client = core_client or CoreApiClient(settings)
    register_error_handlers(app)

    @app.middleware("http")
    async def record_request_latency(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        started = time.perf_counter()
        status_code = 500
        try:
            response = await call_next(request)
            status_code = response.status_code
            return response
        finally:
            route = request.scope.get("route")
            template = getattr(route, "path", "unmatched")
            metrics.http_duration.labels(request.method, template, str(status_code)).observe(
                time.perf_counter() - started
            )

    app.include_router(health.router)
    app.include_router(metrics_api.router)
    app.include_router(diagnostics.router)
    app.include_router(parse_resume.router)
    app.include_router(score_matches.router)
    app.include_router(tailor_resume.router)
    app.include_router(writing.router)
    app.include_router(interview.router)
    app.include_router(mock_interview.router)
    setup_tracing(app, settings, span_exporter)
    return app
