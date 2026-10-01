package com.jobfinder.core.ingestion;

/**
 * Manages what each source is asked to fetch (its {@code source_targets}): the ingestion module's second
 * public entry point, used by the admin API. Adding is idempotent, so the same call can be repeated safely.
 */
public interface SourceTargetService {

    /**
     * Makes sure the source has a target with this identifier (an ATS board token, say) and that the target
     * names its employer. An existing target is returned as it is: an admin's choice to disable it is never
     * undone, and the company it already names is kept.
     *
     * @param sourceCode  the code of a registered source, e.g. {@code GREENHOUSE}
     * @param identifier  what the adapter fetches, at most 255 characters
     * @param companyName the employer's display name; its postings are filed under this company
     * @throws UnknownSourceException     if no source is registered under the code
     * @throws InvalidSourceTargetException if the identifier or company name is unusable
     */
    SourceTargetView addTarget(String sourceCode, String identifier, String companyName);
}
