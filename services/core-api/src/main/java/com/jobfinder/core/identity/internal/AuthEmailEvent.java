package com.jobfinder.core.identity.internal;

/**
 * An email the auth flows want sent. Published inside the originating transaction and
 * delivered asynchronously after it commits, so a slow or failing SMTP server never
 * changes an endpoint's response (which would also leak whether an account exists).
 */
sealed interface AuthEmailEvent {

    String to();

    record VerifyEmail(String to, String token) implements AuthEmailEvent {
    }

    record ResetPassword(String to, String token) implements AuthEmailEvent {
    }

    /** Sent instead of a verification email when someone signs up with an address that already has an account. */
    record AccountAlreadyExists(String to) implements AuthEmailEvent {
    }
}
