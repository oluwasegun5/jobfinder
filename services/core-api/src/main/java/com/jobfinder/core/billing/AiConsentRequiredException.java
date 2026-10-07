package com.jobfinder.core.billing;

import java.net.URI;

import org.springframework.http.HttpStatus;

/**
 * The user has not agreed to AI processing of their data (or withdrew that agreement), so no AI call is made for them.
 * HTTP 403 with the stable code {@code ai_consent_required}; the web client sends the user to the consent screen.
 * It is an {@link AiAllowanceException} so every caller that already handles "blocked before the call" handles it.
 */
public class AiConsentRequiredException extends AiAllowanceException {

    public static final String CODE = "ai_consent_required";
    public static final URI TYPE = URI.create("urn:jobfinder:problem:ai-consent-required");

    public AiConsentRequiredException() {
        super(HttpStatus.FORBIDDEN, CODE,
                "AI processing of your data is switched off. Turn it on in Privacy settings to use this feature.");
        withType(TYPE);
    }
}
