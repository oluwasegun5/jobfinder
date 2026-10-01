package com.jobfinder.core.ingestion;

/**
 * The credit a source's terms require wherever its jobs are shown (ADR 0021). Stored on the source
 * ({@code sources.attribution_*}) and handed out with each listing, so the search and job pages only
 * have to render it.
 *
 * @param name  who to credit ("Adzuna")
 * @param text  the wording the terms ask for ("Jobs by Adzuna"), or the name when they only ask for a credit
 * @param url   where the credit links to
 * @param notes the display rules the terms add (link each job to its listing URL, followed links only, ...)
 */
public record SourceAttribution(String name, String text, String url, String notes) {
}
