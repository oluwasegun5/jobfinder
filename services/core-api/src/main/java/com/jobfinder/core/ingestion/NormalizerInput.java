package com.jobfinder.core.ingestion;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What an adapter extracts from one raw posting, in the source's own words: the per-source mapping
 * seam in front of the normalizer. The adapter's only job is to put each fact in the right field
 * (a Greenhouse {@code location.name} into {@code locationText}, a Lever {@code categories.commitment}
 * into {@code employmentType}); the normalizer does the parsing and cleaning, so nothing here has to
 * be tidy. Every field except {@code title} may be null, and so may {@code companyName} when the
 * fetch target already names its company.
 *
 * <ul>
 * <li>{@code description}: HTML, HTML-escaped HTML (Greenhouse) or plain text, as received;
 * <li>{@code locationText}: free text such as "Austin, TX", "Remote - US" or "Lagos / Hybrid";
 * <li>{@code remote}: the source's own remote flag, when it has one (true or false), else null;
 * <li>{@code employmentType}: the source's label ("Full-time", "CONTRACTOR", "full_time");
 * <li>{@code salaryMin}, {@code salaryMax}, {@code salaryCurrency}, {@code salaryPeriod}: structured
 * compensation when the source has it; {@code salaryText} is free text ("$120k - $150k a year") used
 * only when no amount is structured. A salary is kept only if its currency is known: an adapter whose
 * source leaves the currency implicit (by country, say) states it.
 * </ul>
 */
public record NormalizerInput(String title, String companyName, String description, String locationText,
        Boolean remote, String employmentType, BigDecimal salaryMin, BigDecimal salaryMax, String salaryCurrency,
        String salaryPeriod, String salaryText, String applyUrl, Instant postedAt, Instant expiresAt) {

    public static Builder builder(String title) {
        return new Builder(title);
    }

    /** Fluent construction, because most sources fill only some of the fields. */
    public static final class Builder {

        private final String title;
        private String companyName;
        private String description;
        private String locationText;
        private Boolean remote;
        private String employmentType;
        private BigDecimal salaryMin;
        private BigDecimal salaryMax;
        private String salaryCurrency;
        private String salaryPeriod;
        private String salaryText;
        private String applyUrl;
        private Instant postedAt;
        private Instant expiresAt;

        private Builder(String title) {
            this.title = title;
        }

        public Builder companyName(String value) {
            this.companyName = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder locationText(String value) {
            this.locationText = value;
            return this;
        }

        public Builder remote(Boolean value) {
            this.remote = value;
            return this;
        }

        public Builder employmentType(String value) {
            this.employmentType = value;
            return this;
        }

        public Builder salary(BigDecimal min, BigDecimal max, String currency, String period) {
            this.salaryMin = min;
            this.salaryMax = max;
            this.salaryCurrency = currency;
            this.salaryPeriod = period;
            return this;
        }

        public Builder salaryText(String value) {
            this.salaryText = value;
            return this;
        }

        public Builder applyUrl(String value) {
            this.applyUrl = value;
            return this;
        }

        public Builder postedAt(Instant value) {
            this.postedAt = value;
            return this;
        }

        public Builder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        public NormalizerInput build() {
            return new NormalizerInput(title, companyName, description, locationText, remote, employmentType,
                    salaryMin, salaryMax, salaryCurrency, salaryPeriod, salaryText, applyUrl, postedAt, expiresAt);
        }
    }
}
