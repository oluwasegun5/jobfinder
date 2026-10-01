"""Deterministic fact check of a tailored resume against its source (PLAN.md section 7)."""

from app.factcheck.checker import (
    CHECKER_VERSION,
    SEVERITY,
    FactCheckResult,
    FactFlag,
    FlagCode,
    Severity,
    check_resume,
)

__all__ = [
    "CHECKER_VERSION",
    "SEVERITY",
    "FactCheckResult",
    "FactFlag",
    "FlagCode",
    "Severity",
    "check_resume",
]
