package com.jobfinder.core.activation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Milestone M2: a CV of the user was parsed. Published by profile after the parse result commits. The digests are
 * computed where the raw values live (profile), so no raw email, phone or file leaves that module.
 *
 * @param userId          the CV's owner
 * @param resumeVersionId the parsed version
 * @param file            the digests of the uploaded file (its SHA-256, taken at upload); null for a CV uploaded before
 *                        the hash was recorded
 * @param cvEmails        the digests of the contact email found in the CV (none or one); unverified
 * @param cvPhones        the digests of the phone numbers found in the CV (at most three); unverified
 * @param at              when it happened
 */
public record ResumeParsed(UUID userId, UUID resumeVersionId, DigestSet file, List<DigestSet> cvEmails,
        List<DigestSet> cvPhones, Instant at) {

    public ResumeParsed {
        cvEmails = cvEmails == null ? List.of() : List.copyOf(cvEmails);
        cvPhones = cvPhones == null ? List.of() : List.copyOf(cvPhones);
    }
}
