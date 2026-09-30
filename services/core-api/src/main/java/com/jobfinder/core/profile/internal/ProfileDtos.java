package com.jobfinder.core.profile.internal;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.jobfinder.core.profile.internal.ResumeContentDtos.Link;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request/response shapes for the profile and preferences endpoints. There is deliberately no user ID in any
 * request: the owner is always the authenticated caller.
 */
final class ProfileDtos {

    private ProfileDtos() {
    }

    enum Seniority {
        INTERN, JUNIOR, MID, SENIOR, LEAD, EXECUTIVE
    }

    enum WorkMode {
        REMOTE, HYBRID, ONSITE
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProfileRequest(
            @Size(max = 200) String fullName,
            @Size(max = 300) String headline,
            @Size(max = 200) String location,
            @Size(max = 50) @Pattern(regexp = "^[0-9+()./\\-\\s]*$", message = "may contain only digits and + ( ) . / -") String phone,
            @Size(max = 10) List<@Valid Link> links,
            @Min(0) @Max(80) Integer yearsExperience,
            Seniority seniority) {

        ProfileRequest {
            fullName = CleanText.orNull(fullName);
            headline = CleanText.orNull(headline);
            location = CleanText.orNull(location);
            phone = CleanText.orNull(phone);
            links = CleanText.nonNull(links);
        }
    }

    /**
     * {@code onboardingCompleted} becomes true once the user has saved their preferences, the last onboarding step
     * (saving them empty counts: it is how a user skips the step).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProfileResponse(String fullName, String headline, String location, String phone, List<Link> links,
            Integer yearsExperience, Seniority seniority, boolean onboardingCompleted, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PreferencesRequest(
            @Size(max = 20) List<@NotBlank @Size(max = 100) String> targetTitles,
            @Size(max = 20) List<@NotBlank @Size(max = 100) String> locations,
            @Size(max = 3) List<WorkMode> workModes,
            @Min(0) @Max(100_000_000) Integer minSalary,
            @Size(max = 3) @Pattern(regexp = "^([A-Za-z]{3})?$", message = "must be a 3-letter currency code") String currency,
            Boolean needsSponsorship,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> excludedCompanies,
            @Size(max = 30) List<@NotBlank @Size(max = 100) String> excludedIndustries) {

        PreferencesRequest {
            targetTitles = CleanText.list(targetTitles);
            locations = CleanText.list(locations);
            workModes = CleanText.nonNull(workModes).stream().distinct().toList();
            String code = CleanText.orNull(currency);
            currency = code == null ? null : code.toUpperCase(java.util.Locale.ROOT);
            needsSponsorship = Boolean.TRUE.equals(needsSponsorship);
            excludedCompanies = CleanText.list(excludedCompanies);
            excludedIndustries = CleanText.list(excludedIndustries);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PreferencesResponse(List<String> targetTitles, List<String> locations, List<WorkMode> workModes,
            Integer minSalary, String currency, boolean needsSponsorship, List<String> excludedCompanies,
            List<String> excludedIndustries, Instant updatedAt) {
    }
}
