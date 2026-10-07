"""Masks what is recognisable by its shape (emails, tokens, keys) in anything leaving the process.

The rule is to never pass such values to a logger; this catches the slip. It cannot recognise free
text such as a CV, so it also caps the length of a message. Mirrors core-api's PiiRedactor.
"""

import logging
import re

MAX_LENGTH = 4000
_TRUNCATED = "...[truncated]"

_EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}")
_JWT = re.compile(r"eyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]*")
_BEARER = re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/\-]+=*")
_PROVIDER_KEY = re.compile(
    r"\b(?:sk-ant-[A-Za-z0-9_\-]{8,}|(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{6,}|whsec_[A-Za-z0-9]{6,}"
    r"|pa-[A-Za-z0-9_\-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_\-]{20,})"
)
_NAMED_SECRET = re.compile(
    r"(?i)([\"']?(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token"
    r"|token|authorization|x-service-token|cookie|set-cookie|signature|stripe-signature"
    r"|x-paystack-signature)[\"']?\s*[=:]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,;&\"'}]+)"
)
_PHONE = re.compile(r"(?<![\w+])\+\d[\d\s().\-]{7,}\d")


def redact(text: str) -> str:
    if not text:
        return text
    result = _JWT.sub("[jwt]", text)
    result = _BEARER.sub(lambda m: f"{m.group(1)} [redacted]", result)
    result = _PROVIDER_KEY.sub("[key]", result)
    result = _NAMED_SECRET.sub(lambda m: f"{m.group(1)}[redacted]", result)
    result = _EMAIL.sub("[email]", result)
    result = _PHONE.sub("[phone]", result)
    return result[:MAX_LENGTH] + _TRUNCATED if len(result) > MAX_LENGTH else result


class RedactingFilter(logging.Filter):
    """Redacts the formatted message of every record, so arguments are covered too."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = redact(record.getMessage())
        record.args = None
        return True
