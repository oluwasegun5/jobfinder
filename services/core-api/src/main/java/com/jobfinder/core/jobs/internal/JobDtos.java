package com.jobfinder.core.jobs.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes of the job endpoints. Nothing here carries a user ID: the caller is the
 * authenticated user. Absent values are omitted, not sent as null (the generated client types them optional).
 */
final class JobDtos {

    /** The largest page a client can ask for. */
    static final int MAX_LIMIT = 50;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_QUERY_LENGTH = 200;

    private JobDtos() {
    }

    enum WorkMode {
        REMOTE, HYBRID, ONSITE
    }

    enum EmploymentType {
        FULL_TIME, PART_TIME, CONTRACT, TEMPORARY, INTERNSHIP
    }

    enum Seniority {
        INTERN, JUNIOR, MID, SENIOR, LEAD, EXECUTIVE
    }

    /**
     * Query parameters of {@code GET /jobs} and {@code GET /jobs/{id}/similar}. Lists take repeated parameters
     * ({@code workMode=REMOTE&workMode=HYBRID}): a job matches if it has any of them. Different filters all apply.
     */
    record SearchParams(
            @Size(max = MAX_QUERY_LENGTH) String q,
            @Size(max = 3) List<WorkMode> workMode,
            @Size(max = 5) List<EmploymentType> employmentType,
            @Size(max = 6) List<Seniority> seniority,
            @Size(max = 20) List<@Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter country code") String> country,
            @Size(max = 200) String location,
            UUID companyId,
            @Positive BigDecimal minSalary,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code") String salaryCurrency,
            @Min(1) @Max(365) Integer postedWithinDays,
            @Min(1) @Max(MAX_LIMIT) Integer limit,
            @Size(max = 600) String cursor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CompanyRef(UUID id, String name) {
    }

    /** The salary as the source stated it: {@code period} is HOUR, DAY, WEEK, MONTH or YEAR. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Salary(BigDecimal min, BigDecimal max, String currency, String period) {
    }

    /**
     * A job in a list. {@code summary} is the start of the description as plain text. {@code similarity} is set
     * only in similar-jobs results (1 is identical). {@code savedAt} only in the saved list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JobSummary(UUID id, String title, CompanyRef company, String location, String city, String country,
            String workMode, String employmentType, String seniority, Salary salary, Instant postedAt, String status,
            String summary, boolean saved, Double similarity, Instant savedAt) {

        JobSummary withSimilarity(Double value) {
            return new JobSummary(id, title, company, location, city, country, workMode, employmentType, seniority,
                    salary, postedAt, status, summary, saved, value, savedAt);
        }

        JobSummary withSavedAt(Instant value) {
            return new JobSummary(id, title, company, location, city, country, workMode, employmentType, seniority,
                    salary, postedAt, status, summary, saved, similarity, value);
        }
    }

    /** One page of jobs. {@code nextCursor} is absent on the last page; pass it back as {@code cursor} unchanged. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JobPage(List<JobSummary> items, String nextCursor) {
    }

    /** The credit a source's terms require wherever its jobs are shown (ADR 0021). Must be displayed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Attribution(String name, String text, String url, String notes) {
    }

    /** One source's listing of the job. {@code url} is that source's own page for the posting. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Listing(String source, String sourceKind, String url, Attribution attribution) {
    }

    /**
     * A job in full. {@code description} is plain text (the normalizer's {@code description_text}); no HTML is
     * exposed. {@code similarAvailable} is false until the job has an embedding. {@code listings} are oldest
     * first, and every attribution in them must be shown with the job.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JobDetail(UUID id, String title, CompanyRef company, String location, String city, String country,
            String workMode, String employmentType, String seniority, Salary salary, Instant postedAt,
            Instant expiresAt, String status, String description, List<String> skills, String applyUrl,
            List<Listing> listings, boolean saved, boolean hidden, boolean similarAvailable) {
    }
}
