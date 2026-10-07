package com.jobfinder.core.identity;

import java.util.UUID;

/**
 * Whether a user has agreed to the processing of their data by AI models (docs/adr/0040). Without it no AI call is made
 * for the user: billing's gate refuses it and the resume upload is refused before it stores anything.
 */
public interface AiConsent {

    /** True when the user accepted the AI processing terms and has not withdrawn them. False for an unknown user. */
    boolean isGranted(UUID userId);
}
