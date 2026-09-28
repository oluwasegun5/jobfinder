import hmac
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import APIKeyHeader

SERVICE_TOKEN_HEADER = "X-Service-Token"  # noqa: S105 - header name, not a secret

_service_token_header = APIKeyHeader(name=SERVICE_TOKEN_HEADER, auto_error=False)


async def require_service_token(
    request: Request,
    token: Annotated[str | None, Depends(_service_token_header)],
) -> None:
    """Reject any request that does not carry the shared core-api service token."""
    expected: str = request.app.state.settings.ai_service_token.get_secret_value()
    if token is None or not hmac.compare_digest(token.encode(), expected.encode()):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid service token",
        )
