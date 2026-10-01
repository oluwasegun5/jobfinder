package com.jobfinder.core.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.jobs.JobSelection;
import com.jobfinder.core.matching.internal.MatchingProperties.Batch;
import com.jobfinder.core.matching.internal.MatchingProperties.Llm;
import com.jobfinder.core.matching.internal.MatchingProperties.Retention;
import com.jobfinder.core.matching.internal.MatchingProperties.Weights;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidatePreferences;

/** How saved preferences become stage-1 filters: unset means no filter, and the free text is read forgivingly. */
class PreferenceFiltersTests {

    private static PreferenceFilters filters(int band) {
        return new PreferenceFilters(new MatchingProperties("match_scoring/v1", 300, 30, new Weights(0.6, 0.3, 0.1),
                Duration.ofDays(21), band, new Llm(10, 3000, Duration.ofSeconds(2), Duration.ofSeconds(120)),
                new Retention(Duration.ofDays(14), Duration.ofDays(90)),
                new Batch(true, "0 30 2 * * *", "UTC", Duration.ofDays(14), 500, 3000, 100, Duration.ofHours(2))));
    }

    private static Candidate candidate(String seniority, CandidatePreferences preferences, boolean has) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), "{}", seniority, null, preferences, has);
    }

    private static CandidatePreferences prefs(List<String> locations, List<String> modes, Integer salary,
            String currency, List<String> companies, List<String> industries) {
        return new CandidatePreferences(List.of(), locations, modes, salary, currency, false, companies, industries);
    }

    @Test
    void aUserWithNoPreferencesAndNoSeniorityIsNotFilteredAtAll() {
        JobSelection s = filters(1).selectionFor(candidate(null, CandidatePreferences.EMPTY, false));

        assertThat(s.workModes()).isEmpty();
        assertThat(s.locationTerms()).isEmpty();
        assertThat(s.countryCodes()).isEmpty();
        assertThat(s.minAnnualSalary()).isNull();
        assertThat(s.seniorities()).isEmpty();
        assertThat(s.excludedCompanies()).isEmpty();
        assertThat(s.excludedIndustries()).isEmpty();
    }

    @Test
    void locationsAreReadAsCityCountryNameCountryCodeAndPhrase() {
        JobSelection s = filters(1).selectionFor(candidate(null,
                prefs(List.of(" Lagos ", "Nigeria", "de", "United  Kingdom", ""), List.of(), null, null, List.of(),
                        List.of()), true));

        assertThat(s.locationTerms()).containsExactly("lagos", "nigeria", "de", "united kingdom");
        assertThat(s.countryCodes()).containsExactlyInAnyOrder("NG", "DE", "GB");
    }

    @Test
    void workModesAndCurrencyAreUpperCasedAndTheSalaryNeedsItsCurrency() {
        Candidate withBoth = candidate(null, prefs(List.of(), List.of("remote", " Hybrid"), 80000, " usd ", List.of(),
                List.of()), true);
        Candidate noCurrency = candidate(null, prefs(List.of(), List.of(), 80000, null, List.of(), List.of()), true);

        JobSelection a = filters(1).selectionFor(withBoth);
        JobSelection b = filters(1).selectionFor(noCurrency);

        assertThat(a.workModes()).containsExactly("REMOTE", "HYBRID");
        assertThat(a.minAnnualSalary()).isEqualTo(80000);
        assertThat(a.salaryCurrency()).isEqualTo("USD");
        assertThat(b.minAnnualSalary()).isNull();
        assertThat(b.salaryCurrency()).isNull();
    }

    @Test
    void excludedCompaniesAlsoMatchTheirPunctuationFreeForm() {
        JobSelection s = filters(1).selectionFor(candidate(null,
                prefs(List.of(), List.of(), null, null, List.of("Acme, Inc.", " "), List.of(" Gambling ")), true));

        assertThat(s.excludedCompanies()).containsExactly("acme, inc.", "acme inc");
        assertThat(s.excludedIndustries()).containsExactly("gambling");
    }

    @Test
    void theSeniorityBandIsAroundTheCandidatesLevelAndConfigurable() {
        assertThat(filters(1).seniorities("SENIOR")).containsExactly("MID", "SENIOR", "LEAD");
        assertThat(filters(1).seniorities("senior")).containsExactly("MID", "SENIOR", "LEAD");
        assertThat(filters(0).seniorities("SENIOR")).containsExactly("SENIOR");
        assertThat(filters(1).seniorities("INTERN")).containsExactly("INTERN", "JUNIOR");
        assertThat(filters(1).seniorities("EXECUTIVE")).containsExactly("LEAD", "EXECUTIVE");
        assertThat(filters(3).seniorities("MID")).isEmpty();
        assertThat(filters(1).seniorities("WIZARD")).isEmpty();
        assertThat(filters(1).seniorities(null)).isEmpty();
    }
}
