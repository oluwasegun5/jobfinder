package com.jobfinder.core.identity.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the endpoint-class rate limit on every controller handler. */
@Configuration
class RateLimitWebConfig implements WebMvcConfigurer {

    private final EndpointRateLimitInterceptor interceptor;

    RateLimitWebConfig(EndpointRateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
