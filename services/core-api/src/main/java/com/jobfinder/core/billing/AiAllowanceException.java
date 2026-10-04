package com.jobfinder.core.billing;

import org.springframework.http.HttpStatus;

import com.jobfinder.core.shared.ApiException;

/**
 * A user-attributed AI call was refused before it started because the user may not spend right now: either today's
 * cap is used up ({@link AiDailyCapReachedException}, 429) or the credit balance is spent
 * ({@link InsufficientCreditsException}, 402). Callers that handle "blocked by allowance" catch this type.
 */
public abstract class AiAllowanceException extends ApiException {

    protected AiAllowanceException(HttpStatus status, String code, String detail) {
        super(status, code, detail);
    }
}
