package com.jobfinder.core.activation;

import java.util.List;

/**
 * One canonical value digested under every live pepper version: the current version first (what new claims are written
 * with), then the older ones (what existing claims may still be stored under).
 */
public record DigestSet(ValueType type, List<IdentityDigest> digests) {

    public DigestSet {
        if (type == null || digests == null || digests.isEmpty()) {
            throw new IllegalArgumentException("A digest set needs a type and at least one digest");
        }
        digests = List.copyOf(digests);
    }

    /** The digest under the current pepper version. */
    public IdentityDigest current() {
        return digests.get(0);
    }
}
