package com.jobfinder.core.jobs.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The normalized filters of a search: lists de-duplicated and sorted, text trimmed and case-folded. {@link #key()}
 * is a canonical form of them, which a cursor carries so it cannot be replayed against a different query.
 */
record JobFilters(List<String> workModes, List<String> employmentTypes, List<String> seniorities,
        List<String> countries, String city, UUID companyId, BigDecimal minSalary, String currency,
        Integer postedWithinDays) {

    static JobFilters of(Collection<? extends Enum<?>> workModes, Collection<? extends Enum<?>> employmentTypes,
            Collection<? extends Enum<?>> seniorities, Collection<String> countries, String location,
            UUID companyId, BigDecimal minSalary, String currency, Integer postedWithinDays) {
        String city = location == null ? null : location.strip().toLowerCase(Locale.ROOT);
        return new JobFilters(names(workModes), names(employmentTypes), names(seniorities),
                strings(countries, true), city == null || city.isEmpty() ? null : city, companyId,
                minSalary == null ? null : minSalary.stripTrailingZeros(),
                currency == null || currency.isBlank() ? null : currency.strip().toUpperCase(Locale.ROOT),
                postedWithinDays);
    }

    private static List<String> names(Collection<? extends Enum<?>> values) {
        return values == null ? List.of() : values.stream().map(Enum::name).distinct().sorted().toList();
    }

    private static List<String> strings(Collection<String> values, boolean upper) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank())
                .map(v -> upper ? v.strip().toUpperCase(Locale.ROOT) : v.strip()).distinct().sorted().toList();
    }

    /** A canonical, stable text form of the filters (equal filters, equal key). */
    String key() {
        List<String> parts = new ArrayList<>();
        parts.add("w=" + String.join(",", workModes));
        parts.add("e=" + String.join(",", employmentTypes));
        parts.add("s=" + String.join(",", seniorities));
        parts.add("c=" + String.join(",", countries));
        parts.add("l=" + (city == null ? "" : city));
        parts.add("co=" + (companyId == null ? "" : companyId));
        parts.add("ms=" + (minSalary == null ? "" : minSalary.toPlainString()));
        parts.add("cur=" + (currency == null ? "" : currency));
        parts.add("pw=" + (postedWithinDays == null ? "" : postedWithinDays));
        return parts.stream().collect(Collectors.joining("|"));
    }
}
