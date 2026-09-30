package com.jobfinder.core.profile.internal;

/**
 * Where a resume is in CV parsing. Uploads start PENDING and move exactly once, to PARSED or to
 * FAILED (with {@code parse_error} saying why); only {@link ResumeParseStore} makes that move.
 */
enum ParseStatus {
    PENDING, PARSED, FAILED
}
