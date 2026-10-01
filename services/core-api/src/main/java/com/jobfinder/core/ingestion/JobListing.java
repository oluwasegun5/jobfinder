package com.jobfinder.core.ingestion;

/**
 * One source's listing of a job: where it lives ({@code listingUrl}, the source's own page or redirect for
 * the posting) and the credit the source requires ({@code attribution}, null for a source that asks for none,
 * such as an employer's own job board).
 */
public record JobListing(String sourceCode, SourceKind sourceKind, String listingUrl, SourceAttribution attribution) {
}
