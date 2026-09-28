import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

from app.api import diagnostics, health
from app.api.errors import register_error_handlers
from app.config import Settings, get_settings
from app.llm import LLMProvider, build_provider
from app.security import require_service_token
from app.workers.consumer import AmqpParams, ConsumerState, RabbitConsumer


def create_app(
    settings: Settings | None = None,
    *,
    provider: LLMProvider | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    logging.basicConfig(level=settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        consumer: RabbitConsumer | None = None
        if settings.rabbitmq_enabled:
            consumer = RabbitConsumer(
                AmqpParams(
                    host=settings.rabbitmq_host,
                    port=settings.rabbitmq_port,
                    login=settings.rabbitmq_user,
                    password=settings.rabbitmq_password.get_secret_value(),
                    virtualhost=settings.rabbitmq_vhost,
                ),
                prefetch=settings.rabbitmq_prefetch,
                reconnect_seconds=settings.rabbitmq_reconnect_seconds,
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
            await app.state.llm_provider.aclose()

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
    app.state.llm_provider = provider or build_provider(settings)
    register_error_handlers(app)
    app.include_router(health.router)
    app.include_router(diagnostics.router)
    return app
