"""Schema-validated generation: one retry on validation failure, then fail loudly."""

import logging
from dataclasses import dataclass, replace

from pydantic import BaseModel, ValidationError

from app.llm.base import LLMOutputValidationError, LLMProvider, LLMRequest, LLMUsage

logger = logging.getLogger(__name__)

_MAX_ATTEMPTS = 2


@dataclass(frozen=True, slots=True)
class StructuredResult[T: BaseModel]:
    value: T
    # One entry per LLM call made (every attempt is billable and goes to the ledger).
    usage: list[LLMUsage]


def _strip_code_fence(text: str) -> str:
    stripped = text.strip()
    if stripped.startswith("```") and stripped.endswith("```"):
        body = stripped[3:-3]
        # Drop an optional language tag on the opening fence line.
        _, _, rest = body.partition("\n")
        return rest.strip()
    return stripped


async def generate_structured[T: BaseModel](
    provider: LLMProvider, request: LLMRequest, schema: type[T]
) -> StructuredResult[T]:
    usage: list[LLMUsage] = []
    current = request
    last_error: ValidationError | None = None
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        response = await provider.generate(current)
        usage.append(response.usage)
        try:
            return StructuredResult(
                value=schema.model_validate_json(_strip_code_fence(response.text)), usage=usage
            )
        except ValidationError as e:
            last_error = e
            logger.warning(
                "LLM output failed %s validation (feature=%s, attempt=%d, errors=%d)",
                schema.__name__,
                request.feature,
                attempt,
                e.error_count(),
            )
            # Only field locations and error types go back to the model, never its own output.
            problems = "; ".join(
                f"{'.'.join(str(p) for p in err['loc']) or '<root>'}: {err['type']}"
                for err in e.errors()
            )
            current = replace(
                request,
                user_message=(
                    f"{request.user_message}\n\nYour previous reply was not valid JSON for the "
                    f"required schema ({problems}). Reply with only the JSON object."
                ),
            )
    raise LLMOutputValidationError(
        f"{schema.__name__} validation failed after {_MAX_ATTEMPTS} attempts"
    ) from last_error
