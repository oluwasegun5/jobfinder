from typing import Literal

from fastapi import APIRouter, Request, Response, status
from pydantic import BaseModel

from app.workers.consumer import ConsumerState

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: Literal["UP", "DOWN"]
    rabbitmq: ConsumerState


@router.get("/health", response_model=HealthResponse)
async def health(request: Request, response: Response) -> HealthResponse:
    rabbitmq: ConsumerState = request.app.state.consumer_state()
    up = rabbitmq in (ConsumerState.UP, ConsumerState.DISABLED)
    if not up:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return HealthResponse(status="UP" if up else "DOWN", rabbitmq=rabbitmq)
