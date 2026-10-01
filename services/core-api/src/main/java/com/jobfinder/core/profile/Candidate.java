package com.jobfinder.core.profile;

import java.util.UUID;

/**
 * A candidate as matching sees them.
 *
 * @param resumeVersionId  the primary resume's latest version with content: the unit scores are cached against
 * @param structuredJson   that version's structured content (the parsed CV JSON), contact block included, so a
 *                         consumer must not forward it as is
 * @param seniority        the profile's seniority ({@code INTERN} to {@code EXECUTIVE}), or null if not set
 * @param yearsExperience  the profile's years of experience, or null if not set
 * @param preferences      the saved preferences; {@link CandidatePreferences#EMPTY} when the user has none
 * @param hasPreferences   whether a preferences row exists (saving them is what completes onboarding)
 */
public record Candidate(UUID userId, UUID resumeVersionId, String structuredJson, String seniority,
        Integer yearsExperience, CandidatePreferences preferences, boolean hasPreferences) {
}
