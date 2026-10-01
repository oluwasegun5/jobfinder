"""RFC 7807 problem responses for LLM failures and unusable input files.

`code` is a stable machine-readable identifier (core-api stores it as the failure reason);
`retryable` says whether repeating the same request could succeed.
"""

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.schemas import UsageRecord
from app.llm import (
    LLMConfigurationError,
    LLMError,
    LLMOutputValidationError,
    LLMProviderError,
    LLMRefusalError,
)
from app.parsing.errors import ResumeInputError

logger = logging.getLogger(__name__)

_PROBLEM_JSON = "application/problem+json"


def _problem(
    status: int,
    title: str,
    detail: str,
    *,
    code: str,
    retryable: bool = False,
    usage: list[UsageRecord] | None = None,
) -> JSONResponse:
    # `usage` lists LLM calls that were billed before the failure; core-api records them anyway.
    return JSONResponse(
        status_code=status,
        media_type=_PROBLEM_JSON,
        content={
            "type": "about:blank",
            "title": title,
            "status": status,
            "detail": detail,
            "code": code,
            "retryable": retryable,
            "usage": [u.model_dump(mode="json") for u in usage or []],
        },
    )


async def _handle_llm_error(request: Request, exc: Exception) -> JSONResponse:
    logger.warning("LLM error on %s: %s", request.url.path, type(exc).__name__)
    assert isinstance(exc, LLMError)  # noqa: S101 - narrowing for the type checker
    usage = [UsageRecord.from_usage(u) for u in exc.usage]
    match exc:
        case LLMConfigurationError():
            return _problem(
                503, "LLM provider not configured", str(exc), code="llm_not_configured", usage=usage
            )
        case LLMProviderError():
            return _problem(
                502,
                "LLM provider error",
                str(exc),
                code="llm_unavailable",
                retryable=exc.retryable,
                usage=usage,
            )
        case LLMRefusalError():
            return _problem(
                422, "LLM refused the request", str(exc), code="llm_refused", usage=usage
            )
        case LLMOutputValidationError():
            return _problem(
                502,
                "LLM output failed validation",
                str(exc),
                code="llm_output_invalid",
                retryable=True,
                usage=usage,
            )
        case _:
            return _problem(
                500, "LLM error", "Unexpected LLM failure", code="llm_error", usage=usage
            )


async def _handle_input_error(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, ResumeInputError)  # noqa: S101 - narrowing for the type checker
    logger.info("Unusable input on %s: %s", request.url.path, exc.code)
    return _problem(exc.status_code, "Unusable file", exc.message, code=exc.code)


def register_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(LLMError, _handle_llm_error)
    app.add_exception_handler(ResumeInputError, _handle_input_error)
