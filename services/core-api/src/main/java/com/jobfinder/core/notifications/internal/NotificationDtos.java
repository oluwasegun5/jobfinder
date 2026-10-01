package com.jobfinder.core.notifications.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes of the notification endpoints. Nothing carries a user ID: the caller is the authenticated
 * user. A saved search's criteria use the names and value sets of the {@code GET /jobs} filters.
 */
final class NotificationDtos {

    private NotificationDtos() {
    }

    enum SearchWorkMode {
        REMOTE, HYBRID, ONSITE
    }

    enum SearchEmploymentType {
        FULL_TIME, PART_TIME, CONTRACT, TEMPORARY, INTERNSHIP
    }

    enum SearchSeniority {
        INTERN, JUNIOR, MID, SENIOR, LEAD, EXECUTIVE
    }

    /** How often a saved search mails its new jobs. INSTANT checks every few minutes; OFF keeps the search unmailed. */
    enum SavedSearchFrequency {
        INSTANT, DAILY, WEEKLY, OFF
    }

    enum DigestFrequency {
        DAILY, WEEKLY
    }

    /** What an unsubscribe link switches off (docs/adr/0028-notifications.md). */
    enum UnsubscribeScope {
        /** Alerts for one saved search. */
        SAVED_SEARCH,
        /** Every digest: the "For you" digest and the digests of all saved searches. */
        DIGESTS,
        /** Instant alerts: "For you" matches and saved searches set to INSTANT. */
        INSTANT_ALERTS,
        /** Every optional email JobFinder sends: all of the above. */
        MARKETING
    }

    /** The filters of {@code GET /jobs} that a saved search keeps. Empty lists and absent values do not filter. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SavedSearchCriteria(
            @Size(max = 200) String q,
            @Size(max = 3) List<SearchWorkMode> workMode,
            @Size(max = 5) List<SearchEmploymentType> employmentType,
            @Size(max = 6) List<SearchSeniority> seniority,
            @Size(max = 20) List<@Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter country code") String> country,
            @Size(max = 200) String location,
            @Positive BigDecimal minSalary,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code") String salaryCurrency) {
    }

    record SavedSearchRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Valid SavedSearchCriteria criteria,
            @NotNull SavedSearchFrequency frequency) {
    }

    /** {@code lastRunAt} is the watermark: jobs stored after it are the ones the next email is about. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SavedSearchView(UUID id, String name, SavedSearchCriteria criteria, SavedSearchFrequency frequency,
            Instant lastRunAt, Instant createdAt) {
    }

    record SavedSearchList(List<SavedSearchView> items) {
    }

    /**
     * Notification settings, replaced as a whole. {@code timezone} is an IANA region such as {@code Africa/Lagos}
     * (absent means UTC): the digest goes out at {@code digestHour} there, weekly digests on {@code digestWeekday} (1 is
     * Monday). {@code instantThreshold} is the lowest score (50 to 100) that alerts. Turning digests or alerts on here
     * also clears an earlier email unsubscribe of that kind.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NotificationPreferencesRequest(
            @NotNull Boolean emailEnabled,
            @NotNull Boolean digestEnabled,
            @NotNull DigestFrequency digestFrequency,
            @NotNull @Min(0) @Max(23) Integer digestHour,
            @NotNull @Min(1) @Max(7) Integer digestWeekday,
            @Size(max = 64) String timezone,
            @NotNull Boolean instantEnabled,
            @NotNull @Min(50) @Max(100) Integer instantThreshold) {
    }

    /**
     * The settings as stored, or the defaults (everything optional off) for a user who never saved any.
     * {@code digestsUnsubscribed} and {@code allUnsubscribed} say an email unsubscribe link was used.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NotificationPreferencesView(boolean emailEnabled, boolean digestEnabled, DigestFrequency digestFrequency,
            int digestHour, int digestWeekday, String timezone, boolean instantEnabled, int instantThreshold,
            boolean digestsUnsubscribed, boolean allUnsubscribed) {
    }

    /** What an unsubscribe link would do, shown before it is confirmed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UnsubscribeInfo(UnsubscribeScope scope, String savedSearchName) {
    }
}
