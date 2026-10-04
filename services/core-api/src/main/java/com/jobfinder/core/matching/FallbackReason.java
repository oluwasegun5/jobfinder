package com.jobfinder.core.matching;

/** Why a match has no model score. */
public enum FallbackReason {
    /** The user has used up today's AI allowance. */
    DAILY_CAP_REACHED,
    /** The user has no credits left (HTTP 402 at request time); a plan grant or a top-up restores them. */
    INSUFFICIENT_CREDITS,
    /** ai-service could not be reached or is not configured. */
    LLM_UNAVAILABLE,
    /** The model answered for the batch but not usefully for this job (invalid JSON twice, a refusal, no entry). */
    LLM_FAILED,
    /** The job is no longer active, so no model call is spent on it. */
    JOB_EXPIRED
}
