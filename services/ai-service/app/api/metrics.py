from fastapi import APIRouter, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app.observability.metrics import Metrics

router = APIRouter(tags=["observability"])


@router.get("/metrics", include_in_schema=False)
async def metrics(request: Request) -> Response:
    """Prometheus scrape. Like every route it needs the service token (Prometheus sends it)."""
    registry = request.app.state.metrics
    assert isinstance(registry, Metrics)  # noqa: S101 - set in create_app
    return Response(generate_latest(registry.registry), media_type=CONTENT_TYPE_LATEST)
