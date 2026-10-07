"""Logging setup: optional JSON lines with trace ids, and the redaction filter in every case."""

import json
import logging
import sys
from datetime import UTC, datetime

from opentelemetry import trace

from app.config import Settings
from app.observability.redaction import RedactingFilter, redact

SERVICE_NAME = "ai-service"
_HANDLER_NAME = "jobfinder-observability"


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, object] = {
            "@timestamp": datetime.fromtimestamp(record.created, UTC).isoformat(
                timespec="milliseconds"
            ),
            "log.level": record.levelname,
            "log.logger": record.name,
            "message": record.getMessage(),
            "service.name": SERVICE_NAME,
        }
        context = trace.get_current_span().get_span_context()
        if context.is_valid:
            entry["traceId"] = format(context.trace_id, "032x")
            entry["spanId"] = format(context.span_id, "016x")
        if record.exc_info:
            entry["error.stack_trace"] = redact(self.formatException(record.exc_info))
        return json.dumps(entry, ensure_ascii=False)


class TextFormatter(logging.Formatter):
    """The terminal format, with the trace id when there is one."""

    def __init__(self) -> None:
        super().__init__("%(asctime)s %(levelname)s [%(trace)s] %(name)s: %(message)s")

    def format(self, record: logging.LogRecord) -> str:
        context = trace.get_current_span().get_span_context()
        record.trace = format(context.trace_id, "032x") if context.is_valid else "-"
        text = super().format(record)
        return redact(text) if record.exc_info else text


def configure_logging(settings: Settings) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.set_name(_HANDLER_NAME)
    handler.addFilter(RedactingFilter())
    handler.setFormatter(JsonFormatter() if settings.log_format == "json" else TextFormatter())
    root = logging.getLogger()
    # Only our own handler is replaced, so a test runner's or a host's handlers stay.
    for existing in list(root.handlers):
        if existing.get_name() == _HANDLER_NAME:
            root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(settings.log_level)
