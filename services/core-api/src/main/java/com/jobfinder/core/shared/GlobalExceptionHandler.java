package com.jobfinder.core.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.jobfinder.core.observability.ErrorReporting;

/**
 * Converts every exception into an RFC 7807 {@link ProblemDetail} response. Extending
 * {@link ResponseEntityExceptionHandler} keeps Spring MVC's standard mappings (validation
 * -> 400, unsupported media type -> 415, ...) as problem details; {@link ApiException}
 * covers application errors, and the {@code Exception} fallback means no endpoint ever
 * leaks a raw stack trace.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        if (ex.type() != null) {
            problem.setType(ex.type());
        }
        problem.setProperty("code", ex.code());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.status()).headers(ex.headers()).body(problem);
    }

    /**
     * Bean-validation failures keep Spring's 400 problem detail and add {@code code: validation_failed} and an
     * {@code errors} list of {@code {field, message}}, so clients can point at the offending input. Only the
     * constraint message is echoed, never the rejected value.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Some fields are invalid.");
        problem.setProperty("code", "validation_failed");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> java.util.Map.of("field", error.getField(), "message",
                        String.valueOf(error.getDefaultMessage())))
                .toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied.");
        problem.setProperty("code", "forbidden");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ErrorReporting.capture(ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("An unexpected error occurred.");
        return problem;
    }
}
