package com.jobfinder.core.applications.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where ai-service is and the shared token to send ({@code app.ai-service.*}, the same settings every module that calls
 * it reads; each module has its own record because modules share no internals).
 */
@ConfigurationProperties("app.ai-service")
record ApplicationsAiProperties(String baseUrl, String token) {

    ApplicationsAiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("app.ai-service.base-url (AI_SERVICE_URL) must be set");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("app.ai-service.token (AI_SERVICE_TOKEN) must be set");
        }
    }

    @Override
    public String toString() {
        return "ApplicationsAiProperties[baseUrl=" + baseUrl + "]";
    }
}
