package com.jobfinder.core.ingestion;

/** The tier a source belongs to (PLAN.md section 6). */
public enum SourceKind {
    /** An employer's public applicant-tracking-system job board. */
    ATS,
    /** A job aggregator API. */
    AGGREGATOR,
    /** A site with no API, read by the isolated scraper (only where its terms allow). */
    SCRAPE
}
