"""Sentry, errors only: no default PII, no request data, every event scrubbed. Off without a DSN."""

import logging
from typing import Any

import sentry_sdk

from app.config import Settings
from app.observability.redaction import redact

logger = logging.getLogger(__name__)


def scrub_event(event: Any, hint: Any) -> Any:
    for key in ("request", "user", "server_name", "breadcrumbs", "extra"):
        event.pop(key, None)
    contexts = event.get("contexts")
    if isinstance(contexts, dict):
        contexts.pop("request", None)
    logentry = event.get("logentry")
    if isinstance(logentry, dict):
        logentry["message"] = redact(str(logentry.get("message", "")))
        logentry.pop("params", None)
    message = event.get("message")
    if isinstance(message, str):
        event["message"] = redact(message)
    for exception in (event.get("exception") or {}).get("values", []):
        if isinstance(exception.get("value"), str):
            exception["value"] = redact(exception["value"])
        # Local variables of a frame can hold a CV or a token; only the frames themselves go out.
        for frame in (exception.get("stacktrace") or {}).get("frames", []):
            frame.pop("vars", None)
    return event


def start_sentry(settings: Settings) -> bool:
    if settings.sentry_dsn is None or not settings.sentry_dsn.get_secret_value():
        return False
    sentry_sdk.init(
        dsn=settings.sentry_dsn.get_secret_value(),
        environment=settings.sentry_environment,
        release=settings.sentry_release,
        send_default_pii=False,
        include_local_variables=False,
        max_request_body_size="never",
        traces_sample_rate=0.0,
        before_send=scrub_event,
    )
    logger.info("Sentry error reporting is on (environment %s)", settings.sentry_environment)
    return True
