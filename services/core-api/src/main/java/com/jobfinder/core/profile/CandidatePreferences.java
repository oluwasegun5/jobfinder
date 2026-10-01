package com.jobfinder.core.profile;

import java.util.List;

/**
 * The saved job-search preferences. Empty lists and null values mean "no preference". {@code minSalary} is a yearly
 * amount in {@code currency}; {@code workModes} are {@code REMOTE}, {@code HYBRID} or {@code ONSITE}.
 */
public record CandidatePreferences(List<String> targetTitles, List<String> locations, List<String> workModes,
        Integer minSalary, String currency, boolean needsSponsorship, List<String> excludedCompanies,
        List<String> excludedIndustries) {

    public static final CandidatePreferences EMPTY = new CandidatePreferences(List.of(), List.of(), List.of(), null,
            null, false, List.of(), List.of());
}
