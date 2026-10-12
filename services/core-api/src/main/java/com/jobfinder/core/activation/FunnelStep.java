package com.jobfinder.core.activation;

/** The activation funnel's steps in order (docs/adr/0046-activation-funnel.md). The web app owns the labels. */
public enum FunnelStep {
    SIGNUP,
    EMAIL_VERIFIED,
    CV_UPLOADED,
    CV_PARSED,
    ONBOARDING_COMPLETED,
    FIRST_MATCHES_VIEWED,
    FIRST_TAILORED_CV
}
