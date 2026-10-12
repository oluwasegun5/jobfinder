package com.jobfinder.core.activation;

import java.time.Instant;
import java.util.UUID;

/**
 * Milestone M1: the account's email is verified, through the email link or by a Google sign-in (Google has verified it).
 * Published by identity after the change commits.
 *
 * @param userId        the account
 * @param email         the digests of the account email's claim key; null if it has none (an address the normaliser
 *                      cannot read)
 * @param emailDomain   the email's domain, lower-case ASCII, for the disposable-domain check; null with {@code email}
 * @param googleSubject the digests of the Google account (issuer and subject) when this came from a Google sign-in,
 *                      else null
 * @param at            when it happened
 */
public record EmailVerified(UUID userId, DigestSet email, String emailDomain, DigestSet googleSubject, Instant at) {

    /** Whether the account signed in with Google (a stronger account). */
    public boolean viaGoogle() {
        return googleSubject != null;
    }
}
