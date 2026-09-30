package com.jobfinder.core.profile.internal;

/** Where a resume is in CV parsing. Uploads start PENDING; parsing itself arrives with P1.5. */
enum ParseStatus {
    PENDING, PARSED, FAILED
}
