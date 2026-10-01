package com.jobfinder.core.ingestion.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jobfinder.core.ingestion.internal.NormalizedJob.SalaryPeriod;

/**
 * Normalizes compensation to min / max / ISO 4217 currency / period, from structured fields when the
 * source has them and from free text ("$120k - $150k a year", "45.000 - 55.000 EUR") when it does not.
 * Amounts are kept in the period the source stated, not converted: a monthly figure stays monthly.
 *
 * <p>
 * A salary is dropped, not guessed, when no currency can be found, when an amount is not positive or
 * implausibly large, or when there is no number. When the period is not stated it is inferred only
 * where the magnitude leaves no doubt (10,000 or more: a year; 500 or less: an hour); otherwise it is
 * left null.
 */
final class SalaryParser {

    record Salary(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period) {
    }

    private static final BigDecimal LIMIT = new BigDecimal("100000000000");
    private static final Pattern CODE = Pattern.compile(
            "\\b(usd|gbp|eur|ngn|cad|aud|nzd|inr|zar|kes|ghs|egp|chf|sek|nok|dkk|pln|brl|mxn|sgd|aed|jpy|cny|hkd|"
                    + "czk|huf|ron|try|ils|php|myr|thb|idr|krw|ugx|tzs|rwf|mad)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIXED = Pattern.compile("(?i)(?<![a-z])(us|u\\.s\\.|ca|cad|au|aud|nz|nzd|sg|hk|c|a|s)\\$");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])(\\d[\\d,. ]*\\d|\\d)(\\s?[kK])?(?![\\w])");
    private static final Pattern UP_TO = Pattern.compile("(?i)\\b(up to|max(imum)?|capped at|to a maximum of)\\b");
    private static final Pattern FROM = Pattern.compile("(?i)\\b(from|starting (at|from)|min(imum)?|at least|upwards of)\\b|\\d\\s?[kK]?\\s?\\+");

    private SalaryParser() {
    }

    static Salary parse(BigDecimal min, BigDecimal max, String currency, String period, String text) {
        if (min != null || max != null) {
            return structured(min, max, currency, period, text);
        }
        return text == null || text.isBlank() ? null : fromText(text, currency, period);
    }

    private static Salary structured(BigDecimal min, BigDecimal max, String currency, String period, String text) {
        String code = currencyCode(currency);
        if (code == null && text != null) {
            code = detectCurrency(text);
        }
        SalaryPeriod parsedPeriod = period(period);
        if (parsedPeriod == null && text != null) {
            parsedPeriod = period(text);
        }
        return build(min, max, code, parsedPeriod);
    }

    private static Salary fromText(String text, String currency, String period) {
        String code = currencyCode(currency);
        if (code == null) {
            code = detectCurrency(text);
        }
        List<BigDecimal> amounts = amounts(text);
        if (amounts.isEmpty()) {
            return null;
        }
        BigDecimal low = amounts.get(0);
        BigDecimal high = amounts.size() > 1 ? amounts.get(1) : low;
        if (amounts.size() == 1) {
            if (UP_TO.matcher(text).find()) {
                low = null;
            } else if (FROM.matcher(text).find()) {
                high = null;
            }
        }
        SalaryPeriod parsed = period(period);
        if (parsed == null) {
            parsed = period(text);
        }
        return build(low, high, code, parsed);
    }

    private static Salary build(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period) {
        if (currency == null || (min == null && max == null)) {
            return null;
        }
        if (!plausible(min) || !plausible(max)) {
            return null;
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            BigDecimal swap = min;
            min = max;
            max = swap;
        }
        SalaryPeriod resolved = period;
        if (resolved == null) {
            BigDecimal reference = max != null ? max : min;
            if (reference.compareTo(new BigDecimal("10000")) >= 0) {
                resolved = SalaryPeriod.YEAR;
            } else if (reference.compareTo(new BigDecimal("500")) <= 0) {
                resolved = SalaryPeriod.HOUR;
            }
        }
        return new Salary(min == null ? null : min.setScale(2, java.math.RoundingMode.HALF_UP),
                max == null ? null : max.setScale(2, java.math.RoundingMode.HALF_UP), currency, resolved);
    }

    private static boolean plausible(BigDecimal value) {
        return value == null || (value.signum() > 0 && value.compareTo(LIMIT) < 0);
    }

    /** An ISO code from a field that may hold a code, a symbol or nothing; null if not recognised. */
    static String currencyCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.matches("(?i)[a-z]{3}")) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return detectCurrency(trimmed);
    }

    /** The currency a text names, by code ("EUR") or symbol ("c$", "£"); null if none. */
    static String detectCurrency(String text) {
        Matcher code = CODE.matcher(text);
        if (code.find()) {
            return code.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher prefixed = PREFIXED.matcher(text);
        if (prefixed.find()) {
            return switch (prefixed.group(1).toLowerCase(Locale.ROOT).replace(".", "")) {
                case "c", "ca", "cad" -> "CAD";
                case "a", "au", "aud" -> "AUD";
                case "nz", "nzd" -> "NZD";
                case "s", "sg" -> "SGD";
                case "hk" -> "HKD";
                default -> "USD";
            };
        }
        if (text.contains("£")) {
            return "GBP";
        }
        if (text.contains("€")) {
            return "EUR";
        }
        if (text.contains("₦")) {
            return "NGN";
        }
        if (text.contains("₹")) {
            return "INR";
        }
        if (text.contains("¥")) {
            return "JPY";
        }
        if (text.contains("$")) {
            return "USD";
        }
        return null;
    }

    /** The period a word or phrase names ("per annum", "/hr", "monthly"); null if none. */
    static SalaryPeriod period(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.toLowerCase(Locale.ROOT).replace('_', ' ').replace('\n', ' ');
        if (text.matches(".*\\b(hour|hourly|hr|hrs|ph|p/h)\\b.*") || text.contains("/h ") || text.endsWith("/h")) {
            return SalaryPeriod.HOUR;
        }
        if (text.matches(".*\\b(day|daily|pd|p/d)\\b.*")) {
            return SalaryPeriod.DAY;
        }
        if (text.matches(".*\\b(week|weekly|wk|pw)\\b.*")) {
            return SalaryPeriod.WEEK;
        }
        if (text.matches(".*\\b(month|monthly|mo|pcm|pm)\\b.*")) {
            return SalaryPeriod.MONTH;
        }
        if (text.matches(".*\\b(year|yearly|annual|annually|annum|yr|pa|p/a|per yr)\\b.*") || text.contains("p.a")) {
            return SalaryPeriod.YEAR;
        }
        return null;
    }

    /** The first two amounts in a text, "k" suffixes applied, a lone leading "k" shared across a range ("120-150k"). */
    private static List<BigDecimal> amounts(String text) {
        List<BigDecimal> values = new ArrayList<>();
        List<Boolean> kilo = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find() && values.size() < 2) {
            BigDecimal value = parseNumber(matcher.group(1).trim());
            if (value == null) {
                continue;
            }
            boolean k = matcher.group(2) != null;
            values.add(k ? value.multiply(BigDecimal.valueOf(1000)) : value);
            kilo.add(k);
        }
        if (values.size() == 2 && kilo.get(1) && !kilo.get(0) && values.get(0).compareTo(BigDecimal.valueOf(1000)) < 0) {
            values.set(0, values.get(0).multiply(BigDecimal.valueOf(1000)));
        }
        return values;
    }

    /** "120,000", "45.000", "45.000,50", "1,234.56", "120 000" and "85.5" to numbers; null if it is not one. */
    static BigDecimal parseNumber(String token) {
        String t = token.replace(" ", "");
        if (t.isEmpty() || t.endsWith(",") || t.endsWith(".")) {
            return null;
        }
        boolean comma = t.contains(",");
        boolean dot = t.contains(".");
        try {
            if (comma && dot) {
                boolean commaIsDecimal = t.lastIndexOf(',') > t.lastIndexOf('.');
                t = commaIsDecimal ? t.replace(".", "").replace(',', '.') : t.replace(",", "");
            } else if (comma) {
                t = t.matches("\\d{1,3}(,\\d{3})+") ? t.replace(",", "") : t.replaceAll(",(?=\\d{1,2}$)", ".").replace(",", "");
            } else if (dot && t.matches("\\d{1,3}(\\.\\d{3})+")) {
                t = t.replace(".", "");
            }
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
