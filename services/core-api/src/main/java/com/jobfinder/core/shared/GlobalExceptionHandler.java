package com.jobfinder.core.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts uncaught exceptions into RFC 7807 {@link ProblemDetail} responses.
 * Spring MVC already maps common framework exceptions (validation, 404, etc.)
 * to {@code ProblemDetail} via {@code spring.mvc.problemdetails.enabled}; this
 * advice is the fallback for anything else so no endpoint ever leaks a raw
 * stack trace.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("An unexpected error occurred.");
        return problem;
    }
}
