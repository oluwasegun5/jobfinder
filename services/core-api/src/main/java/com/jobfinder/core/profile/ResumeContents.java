package com.jobfinder.core.profile;

import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the structured content of a user's own resumes, for modules that export it (rendering to PDF or
 * DOCX). Nothing here is writable and nothing is visible across users.
 */
public interface ResumeContents {

    /**
     * The latest version of the user's resume {@code resumeId}, or empty if the user has no such resume (someone
     * else's resume is the same as a missing one). {@link ResumeSnapshot#structuredJson()} is null while the resume
     * has no content yet (parse pending or failed and nothing saved by hand).
     */
    Optional<ResumeSnapshot> latest(UUID userId, UUID resumeId);
}
