package com.jobfinder.core.identity.internal;

import java.time.Duration;

/**
 * The rate-limit class of an endpoint (docs/adr/0037-security-hardening.md). Every handler mapping belongs to exactly one
 * class (see {@link EndpointClassifier}); each class has a default limit, overridable under
 * {@code app.rate-limit.endpoints.<CLASS_NAME>.capacity|period}. Limits are per authenticated user, or per client IP for
 * the endpoints that need no sign-in.
 *
 * <p>{@code failOpen}: whether a Redis outage lets the request through. Only the two catch-all classes fail open (a
 * cache blip must not take the whole API down); everything that costs money or can be brute-forced fails closed, as
 * the login limits always have.
 */
enum EndpointClass {

    /** Endpoints that call an LLM (directly or through ai-service): tailor, letters, answers, prep, mock turns, match. */
    AI(30, Duration.ofMinutes(10), false),
    /** Receiving a file: CV upload. */
    UPLOAD(20, Duration.ofHours(1), false),
    /** Handing a stored file back: pre-signed CV link, rendered-document download. */
    DOWNLOAD(60, Duration.ofMinutes(10), false),
    /** Job search and the ranked feed: heavy queries. */
    SEARCH(120, Duration.ofMinutes(1), false),
    /** Rendering a PDF or DOCX. */
    EXPORT(20, Duration.ofMinutes(10), false),
    /** The Chrome extension's apply-context lookup. */
    EXTENSION(120, Duration.ofMinutes(1), false),
    /** Admin actions that start outbound fetches (manual source runs, new targets). */
    ADMIN_ACTION(20, Duration.ofMinutes(1), false),
    /** Signed-link endpoints that need no sign-in (email unsubscribe): per IP. */
    PUBLIC_LINK(30, Duration.ofHours(1), false),
    /**
     * Provider webhooks ({@code /webhooks/**}): signature authenticated, so the limit is per client IP and generous
     * (a provider redelivering after an outage sends bursts); it exists so unauthenticated traffic cannot make us
     * read bodies and compute signatures without bound. A refusal is a 429, which the provider retries.
     */
    WEBHOOK(300, Duration.ofMinutes(1), false),
    /** Every other authenticated read. */
    API_READ(600, Duration.ofMinutes(1), true),
    /** Every other authenticated write. */
    API_WRITE(120, Duration.ofMinutes(1), true),
    /**
     * Not limited here, with the reason: {@code /auth/**} has its own per-IP and per-account rules
     * ({@link RateLimitRule}); checkout and cancel are limited in the billing service; webhooks have their own
     * per-IP class ({@link #WEBHOOK}); {@code /internal/**} carries the service token; the actuator is health only.
     */
    EXEMPT(0, Duration.ZERO, true);

    private final int defaultCapacity;
    private final Duration defaultPeriod;
    private final boolean failOpen;

    EndpointClass(int defaultCapacity, Duration defaultPeriod, boolean failOpen) {
        this.defaultCapacity = defaultCapacity;
        this.defaultPeriod = defaultPeriod;
        this.failOpen = failOpen;
    }

    int defaultCapacity() {
        return defaultCapacity;
    }

    Duration defaultPeriod() {
        return defaultPeriod;
    }

    boolean failOpen() {
        return failOpen;
    }
}
