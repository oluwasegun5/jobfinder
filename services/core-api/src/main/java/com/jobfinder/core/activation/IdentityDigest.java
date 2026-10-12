package com.jobfinder.core.activation;

import java.util.Arrays;

/**
 * One keyed digest: HMAC-SHA-256 under the pepper of {@code keyVersion}. Never log it next to a user id, email or IP.
 *
 * @param keyVersion the pepper version ({@code TRIAL_PEPPER_V<n>})
 * @param value      the 32-byte digest
 */
public record IdentityDigest(short keyVersion, byte[] value) {

    public IdentityDigest {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException("A digest is 32 bytes");
        }
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    /** Lower-case hex of the digest, for sorting and lock names. */
    public String hex() {
        return IdentityNormaliser.hex(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdentityDigest d && d.keyVersion == keyVersion && Arrays.equals(d.value, value);
    }

    @Override
    public int hashCode() {
        return 31 * keyVersion + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "IdentityDigest[v" + keyVersion + "]";
    }
}
