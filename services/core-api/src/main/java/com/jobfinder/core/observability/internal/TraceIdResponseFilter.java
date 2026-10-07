package com.jobfinder.core.observability.internal;

import java.io.IOException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Puts the id of the current trace on every response as {@code X-Trace-Id}, errors and 401s from the security chain
 * included, so a user's report or a browser network tab leads straight to the trace and to the log lines (they carry
 * the same id). Runs right after Spring's observation filter, which opens the request's span, and before security.
 */
@Component
class TraceIdResponseFilter extends OncePerRequestFilter implements Ordered {

    static final String HEADER = "X-Trace-Id";

    private final ObjectProvider<Tracer> tracer;

    // A provider, not the tracer: a test context may switch tracing off, and then there is nothing to add.
    TraceIdResponseFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public int getOrder() {
        // ServerHttpObservationFilter is at HIGHEST_PRECEDENCE + 1.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span != null) {
            response.setHeader(HEADER, span.context().traceId());
        }
        chain.doFilter(request, response);
    }
}
