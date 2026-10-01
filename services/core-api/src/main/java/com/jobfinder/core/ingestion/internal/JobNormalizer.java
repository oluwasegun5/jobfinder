package com.jobfinder.core.ingestion.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.internal.NormalizedJob.EmploymentType;
import com.jobfinder.core.ingestion.internal.NormalizedJob.Seniority;
import com.jobfinder.core.ingestion.internal.NormalizedJob.WorkMode;

/**
 * Maps what an adapter extracted ({@link NormalizerInput}) to the {@code jobs} schema: cleaned title,
 * description as sanitized HTML and plain text, location, work mode, employment type, seniority and
 * salary, plus the company and fingerprint that deduplication uses. Deterministic rules only; fields
 * the rules cannot read stay null (an LLM pass for those is a later, separate step). Pure: no I/O.
 */
@Component
class JobNormalizer {

    private static final int MAX_TITLE = 500;
    private static final int MAX_COMPANY = 300;
    private static final int MAX_LOCATION = 500;
    private static final int MAX_URL = 2000;

    /**
     * @param knownCompanyName the company the fetch target belongs to, when the target names one; it
     *        wins over whatever the posting says, so a board's jobs are never split across spellings
     * @throws IllegalArgumentException if the posting has no usable title or company
     */
    NormalizedJob normalize(NormalizerInput input, String knownCompanyName) {
        String title = TextCleaner.truncate(TextCleaner.line(input.title()), MAX_TITLE);
        if (title == null) {
            throw new IllegalArgumentException("posting has no title");
        }
        String company = TextCleaner.truncate(
                TextCleaner.line(knownCompanyName != null ? knownCompanyName : input.companyName()), MAX_COMPANY);
        if (company == null) {
            throw new IllegalArgumentException("posting has no company");
        }
        String normalizedCompany = Names.company(company);
        if (normalizedCompany.isEmpty()) {
            throw new IllegalArgumentException("company name has no usable characters");
        }

        String descriptionText = TextCleaner.descriptionText(input.description());
        String descriptionHtml = TextCleaner.sanitizedHtml(input.description());
        LocationParser.Parsed location = LocationParser.parse(input.locationText());
        WorkMode workMode = Inference.workMode(input.remote(), location, title, descriptionText);
        EmploymentType employmentType = Inference.employmentType(input.employmentType(), title, descriptionText);
        Seniority seniority = Inference.seniority(title);
        SalaryParser.Salary salary = SalaryParser.parse(input.salaryMin(), input.salaryMax(), input.salaryCurrency(),
                input.salaryPeriod(), input.salaryText());

        String normalizedTitle = Names.title(title, location.city());
        if (normalizedTitle.isEmpty()) {
            throw new IllegalArgumentException("title has no usable characters");
        }
        String fingerprint = Names.fingerprint(normalizedCompany, normalizedTitle, Names.location(location, workMode));

        return new NormalizedJob(company, normalizedCompany, title, TextCleaner.truncate(normalizedTitle, MAX_TITLE), descriptionHtml, descriptionText,
                TextCleaner.truncate(TextCleaner.line(input.locationText()), MAX_LOCATION), location.city(),
                location.country(), workMode, employmentType, seniority, salary == null ? null : salary.min(),
                salary == null ? null : salary.max(), salary == null ? null : salary.currency(),
                salary == null ? null : salary.period(), applyUrl(input.applyUrl()), input.postedAt(),
                input.expiresAt(), fingerprint);
    }

    /** Absolute http(s) URLs only: a "javascript:" or relative apply link is dropped, not stored. */
    static String applyUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim();
        if (url.isEmpty() || url.length() > MAX_URL || url.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean web = scheme.equals("http") || scheme.equals("https");
            return web && uri.getHost() != null ? url : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
