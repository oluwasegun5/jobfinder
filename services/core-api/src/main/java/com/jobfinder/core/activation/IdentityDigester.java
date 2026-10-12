package com.jobfinder.core.activation;

import java.util.List;
import java.util.Optional;

/**
 * Keyed digests of identity values: {@code HMAC-SHA-256(pepper[version], valueType || 0x1F || canonical)} under every
 * live pepper version (docs/adr/0042-trial-credit-abuse-gate.md, section 3). The peppers come from the environment
 * ({@code TRIAL_PEPPER_V<n>}), never the database or the repository, which is what makes a digest of an email or a
 * phone number pseudonymous rather than reversible by dictionary.
 */
public interface IdentityDigester {

    /** The pepper version new claims are written with. */
    short currentVersion();

    /** {@code canonical} (already normalised) digested under every live version, the current one first. */
    DigestSet digest(ValueType type, String canonical);

    /** The digests of an email address's claim key, or empty when it is not a usable address. */
    default Optional<DigestSet> email(String raw) {
        return IdentityNormaliser.email(raw).map(canonical -> digest(ValueType.EMAIL, canonical));
    }

    /** The digests of every phone number found in {@code text} (at most three). */
    default List<DigestSet> phones(String text) {
        return IdentityNormaliser.phones(text).stream().map(p -> digest(ValueType.PHONE, p)).toList();
    }

    /** The digests of a Google account (issuer and subject). */
    default DigestSet googleSubject(String subject) {
        return digest(ValueType.GOOGLE_SUB, IdentityNormaliser.googleSubject(subject));
    }

    /** The digests of a file, given the SHA-256 of its bytes. */
    default DigestSet file(byte[] sha256) {
        return digest(ValueType.FILE, IdentityNormaliser.file(sha256));
    }
}
