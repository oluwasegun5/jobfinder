"""RFC 7807 problem responses for LLM failures."""

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.llm import (
    LLMConfigurationError,
    LLMError,
    LLMOutputValidationError,
    LLMProviderError,
    LLMRefusalError,
)

logger = logging.getLogger(__name__)

_PROBLEM_JSON = "application/problem+json"


def _problem(status: int, title: str, detail: str, *, retryable: bool = False) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        media_type=_PROBLEM_JSON,
        content={
            "type": "about:blank",
            "title": title,
            "status": status,
            "detail": detail,
            "retryable": retryable,
        },
    )


async def _handle_llm_error(request: Request, exc: Exception) -> JSONResponse:
    logger.warning("LLM error on %s: %s", request.url.path, type(exc).__name__)
    match exc:
        case LLMConfigurationError():
            return _problem(503, "LLM provider not configured", str(exc))
        case LLMProviderError():
            return _problem(502, "LLM provider error", str(exc), retryable=exc.retryable)
        case LLMRefusalError():
            return _problem(422, "LLM refused the request", str(exc))
        case LLMOutputValidationError():
            return _problem(502, "LLM output failed validation", str(exc), retryable=True)
        case _:
            return _problem(500, "LLM error", "Unexpected LLM failure")


def register_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(LLMError, _handle_llm_error)
