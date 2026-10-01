package com.jobfinder.core.jobs;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a saved search looks for: the filters of {@code GET /jobs} that make sense over time (not the page size, the
 * cursor or "posted within"). Empty lists and nulls mean "do not filter on this", as in a search.
 *
 * @param q          a keyword query in web-search syntax, or null
 * @param workModes  {@code REMOTE}, {@code HYBRID}, {@code ONSITE}
 * @param countries  upper-case ISO 3166 alpha-2 codes
 * @param location   a city name, compared case-insensitively
 * @param minSalary  a floor on the top of a stated yearly salary; needs {@code currency}
 * @param currency   upper-case ISO 4217 code of {@code minSalary}
 */
public record JobSearchCriteria(String q, List<String> workModes, List<String> employmentTypes,
        List<String> seniorities, List<String> countries, String location, BigDecimal minSalary, String currency) {
}
