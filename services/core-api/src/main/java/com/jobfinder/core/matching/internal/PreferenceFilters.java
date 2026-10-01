package com.jobfinder.core.matching.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jobfinder.core.jobs.JobSelection;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidatePreferences;

/**
 * Turns a candidate's saved preferences and profile into the stage-1 filters (docs/adr/0026-matching-engine.md).
 *
 * <ul>
 * <li><b>Unset means no filter.</b> Empty lists and null values filter nothing, so a user with no preferences
 * is matched against every active job.</li>
 * <li><b>Unknown is not a mismatch.</b> A job with no value for the field a filter reads passes that filter
 * (the normalizer leaves a field empty when it cannot tell, ADR 0019); ranking, not hiding, deals with such jobs.</li>
 * <li><b>Work modes</b>: the job's mode must be one of the preferred ones.</li>
 * <li><b>Locations</b>: free text. Each entry is read three ways: a city (exact, case-insensitive), a country (an
 * English country name or an ISO alpha-2 code, matched against the job's country code), and a phrase the job's raw
 * location text contains. Remote jobs pass whatever the locations say (work modes decide whether remote is
 * wanted).</li>
 * <li><b>Salary floor</b> ({@code minSalary}, a yearly amount, with its currency): rules out only jobs that state a
 * yearly top salary in that same currency below the floor. Jobs with no salary, no pay period or another currency
 * pass: there is no exchange rate to compare with, and most postings state no salary.</li>
 * <li><b>Seniority band</b>: from the profile's seniority, the same level and {@code app.matching.seniority-band}
 * steps either side of {@code INTERN, JUNIOR, MID, SENIOR, LEAD, EXECUTIVE}.</li>
 * <li><b>Excluded companies</b>: case-insensitive equality with the company's name or its normalized name.
 * <b>Excluded industries</b>: equality with the company's industry (where known).</li>
 * <li><b>Sponsorship</b> is not applied: jobs carry no sponsorship attribute to filter on.</li>
 * <li>Hidden jobs, expired jobs and (by the recall) jobs without an embedding never pass, whatever the preferences.</li>
 * </ul>
 */
@Component
class PreferenceFilters {

    static final List<String> SENIORITY_ORDER = List.of("INTERN", "JUNIOR", "MID", "SENIOR", "LEAD", "EXECUTIVE");

    private static final Map<String, String> COUNTRY_CODES_BY_NAME = countryCodesByName();

    private final int seniorityBand;

    PreferenceFilters(MatchingProperties properties) {
        this.seniorityBand = properties.seniorityBand();
    }

    JobSelection selectionFor(Candidate candidate) {
        UUID userId = candidate.userId();
        CandidatePreferences p = candidate.preferences();

        Set<String> terms = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        for (String location : p.locations()) {
            String term = clean(location);
            if (term.isEmpty()) {
                continue;
            }
            terms.add(term);
            if (term.length() == 2 && COUNTRY_CODES_BY_NAME.containsValue(term.toUpperCase(Locale.ROOT))) {
                codes.add(term.toUpperCase(Locale.ROOT));
            }
            String byName = COUNTRY_CODES_BY_NAME.get(term);
            if (byName != null) {
                codes.add(byName);
            }
        }

        boolean salary = p.minSalary() != null && p.currency() != null && !p.currency().isBlank();
        List<String> companies = new ArrayList<>();
        for (String company : p.excludedCompanies()) {
            String name = clean(company);
            if (!name.isEmpty()) {
                companies.add(name);
                // The normalized form drops punctuation: "Acme, Inc." is stored as "acme".
                String squashed = name.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
                if (!squashed.isEmpty() && !squashed.equals(name)) {
                    companies.add(squashed);
                }
            }
        }
        List<String> industries = p.excludedIndustries().stream().map(PreferenceFilters::clean)
                .filter(s -> !s.isEmpty()).toList();

        return new JobSelection(userId,
                p.workModes().stream().map(s -> s.strip().toUpperCase(Locale.ROOT)).toList(),
                List.copyOf(terms), List.copyOf(codes), salary ? p.minSalary() : null,
                salary ? p.currency().strip().toUpperCase(Locale.ROOT) : null, seniorities(candidate.seniority()),
                companies, industries);
    }

    /** The allowed seniority values around the candidate's own; empty (no filter) if unset or unknown. */
    List<String> seniorities(String candidateSeniority) {
        if (candidateSeniority == null) {
            return List.of();
        }
        int at = SENIORITY_ORDER.indexOf(candidateSeniority.strip().toUpperCase(Locale.ROOT));
        if (at < 0) {
            return List.of();
        }
        int from = Math.max(0, at - seniorityBand);
        int to = Math.min(SENIORITY_ORDER.size() - 1, at + seniorityBand);
        if (from == 0 && to == SENIORITY_ORDER.size() - 1) {
            return List.of();
        }
        return SENIORITY_ORDER.subList(from, to + 1);
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> countryCodesByName() {
        Map<String, String> byName = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            byName.put(new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)
                    .toLowerCase(Locale.ROOT), code);
        }
        // Names people write that the JDK spells differently.
        byName.put("usa", "US");
        byName.put("united states of america", "US");
        byName.put("uk", "GB");
        byName.put("great britain", "GB");
        byName.put("england", "GB");
        return Map.copyOf(byName);
    }
}
