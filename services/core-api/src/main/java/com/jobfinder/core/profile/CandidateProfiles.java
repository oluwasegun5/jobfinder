package com.jobfinder.core.profile;

import java.util.Optional;
import java.util.UUID;

/**
 * What matching needs to know about a candidate, read from the profile module's own data: the content of the
 * primary resume and the saved preferences. Nothing here is writable.
 */
public interface CandidateProfiles {

    /**
     * The candidate's current primary resume (the latest version of the primary resume that has content) with
     * their profile and preferences, or empty if they have no primary resume with parsed content yet.
     */
    Optional<Candidate> candidate(UUID userId);
}
