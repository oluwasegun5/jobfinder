"""Deterministic fact check of a tailored resume against its source (PLAN.md section 7)."""

from app.factcheck.checker import (
    CHECKER_VERSION,
    PLACEHOLDER_RE,
    SEVERITY,
    FactCheckResult,
    FactFlag,
    FlagCode,
    Severity,
    check_resume,
    check_texts,
    total_experience_months,
)

__all__ = [
    "CHECKER_VERSION",
    "PLACEHOLDER_RE",
    "SEVERITY",
    "FactCheckResult",
    "FactFlag",
    "FlagCode",
    "Severity",
    "check_resume",
    "check_texts",
    "total_experience_months",
]
