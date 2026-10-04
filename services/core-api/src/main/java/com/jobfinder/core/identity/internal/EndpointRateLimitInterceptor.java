package com.jobfinder.core.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.jobfinder.core.shared.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies the per-class limits of {@link EndpointClass} to every controller handler, on the same Redis token buckets
 * the login limits use ({@link RateLimiter}). The key is the authenticated user's id, or the client IP when there is no
 * signed-in user; the bucket name is the class, so each class has its own budget per user.
 *
 * <p>Runs after the security filter chain, so an unauthenticated request to a protected endpoint is refused with 401
 * before it can spend anything, and a request from user A never drains user B's bucket.
 */
@Component
class EndpointRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EndpointRateLimitInterceptor.class);

    private final RateLimiter limiter;
    private final RateLimitProperties properties;

    EndpointRateLimitInterceptor(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        EndpointClass endpointClass = EndpointClassifier.classify(request.getMethod(), pattern);
        if (endpointClass == EndpointClass.EXEMPT) {
            return true;
        }
        RateLimitProperties.Limit limit = properties.endpointLimit(endpointClass);
        try {
            limiter.check("endpoint:" + endpointClass.name(), subject(request), limit.capacity(), limit.period());
        } catch (ApiException e) {
            if (e.code().equals("rate_limiter_unavailable") && endpointClass.failOpen()) {
                log.warn("Rate limiter unavailable; letting a {} request through", endpointClass);
                return true;
            }
            throw e;
        }
        return true;
    }

    private static String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.getToken().getSubject() != null) {
            return "user:" + token.getToken().getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
