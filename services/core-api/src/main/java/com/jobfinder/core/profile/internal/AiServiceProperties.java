package com.jobfinder.core.profile.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How to reach the internal ai-service ({@code app.ai-service.*}). The token is the shared secret
 * sent as {@code X-Service-Token}; there is deliberately no default, so startup fails without it.
 */
@ConfigurationProperties("app.ai-service")
record AiServiceProperties(
        String baseUrl,
        String token,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("90s") Duration readTimeout) {

    AiServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("app.ai-service.base-url (AI_SERVICE_URL) must be set");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("app.ai-service.token (AI_SERVICE_TOKEN) must be set");
        }
    }

    @Override
    public String toString() {
        return "AiServiceProperties[baseUrl=" + baseUrl + "]";
    }
}
