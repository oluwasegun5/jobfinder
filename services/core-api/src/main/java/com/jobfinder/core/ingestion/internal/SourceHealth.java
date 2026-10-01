package com.jobfinder.core.ingestion.internal;

/** What the last run says about a source: all targets fetched, some failed, or all failed. */
enum SourceHealth {
    UNKNOWN, HEALTHY, DEGRADED, FAILING
}
