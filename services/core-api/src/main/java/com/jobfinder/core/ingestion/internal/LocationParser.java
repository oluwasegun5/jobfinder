package com.jobfinder.core.ingestion.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the free text a source gives as a location ("Austin, TX", "Remote - US", "Lagos / Hybrid")
 * into a city, an ISO 3166 country code and the work-mode words it contained. Deterministic and
 * table-driven (PLAN.md section 6: rules first): what it does not recognise stays null rather than
 * being guessed. A text naming several places takes the first.
 */
final class LocationParser {

    record Parsed(String city, String country, boolean remote, boolean hybrid, boolean onsite) {
        static final Parsed NONE = new Parsed(null, null, false, false, false);
    }

    private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOTE = Pattern.compile(
            "\\b(remote(ly)?|work from home|wfh|telecommut\\w*|anywhere|worldwide|work from anywhere)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ONSITE = Pattern.compile("\\b(on[- ]?site|in[- ]office|office[- ]based)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILLER = Pattern.compile(
            "\\b((based|located)\\s+in|remote(ly)?|hybrid|fully|on[- ]?site|in[- ]office|office[- ]based|flexible|work from home|"
                    + "wfh|telecommut\\w*|anywhere|worldwide|work from anywhere|based|greater|area|metropolitan|"
                    + "metro|region|time ?zones?|only|eligible|open to|candidates|located|location|hq|"
                    + "headquarters)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ZIP = Pattern.compile("\\b\\d{4,6}(-\\d{4})?\\b");
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("[(\\[{]\\s*[)\\]}]");
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("\\s*(?:;|\\||\\u2022|\\s/\\s|/(?=\\s)|\\s+or\\s+)\\s*");
    private static final Pattern DASH = Pattern.compile("\\s+[-\\u2013\\u2014]\\s+");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\s,.:;()\\[\\]{}\\-\\u2013\\u2014]+|[\\s,.:;()\\[\\]{}\\-\\u2013\\u2014]+$");

    private static final Map<String, String> COUNTRY_BY_NAME = new HashMap<>();
    private static final Set<String> COUNTRY_CODES = new HashSet<>();
    private static final Map<String, String> US_STATES = new HashMap<>();
    private static final Map<String, String> CA_PROVINCES = new HashMap<>();
    private static final Map<String, String[]> CITIES = new HashMap<>();
    private static final Set<String> REGIONS = Set.of("emea", "apac", "latam", "europe", "north america", "americas",
            "asia", "africa", "eu", "european union", "asia pacific", "middle east", "south america", "global",
            "international", "nationwide", "multiple locations", "various", "multiple");

    static {
        String[] countries = {
                "US|United States;United States of America;USA;U.S.;U.S.A.;America;US",
                "GB|United Kingdom;UK;U.K.;Great Britain;England;Scotland;Wales;Northern Ireland;GB",
                "CA|Canada", "AU|Australia", "NZ|New Zealand", "IE|Ireland;Republic of Ireland",
                "DE|Germany;Deutschland", "FR|France", "ES|Spain;Espana", "PT|Portugal", "IT|Italy;Italia",
                "NL|Netherlands;The Netherlands;Holland", "BE|Belgium", "CH|Switzerland", "AT|Austria",
                "SE|Sweden", "NO|Norway", "DK|Denmark", "FI|Finland", "PL|Poland", "CZ|Czech Republic;Czechia",
                "RO|Romania", "UA|Ukraine", "GR|Greece", "TR|Turkey;Turkiye", "IL|Israel", "AE|United Arab Emirates;UAE",
                "SA|Saudi Arabia", "EG|Egypt", "ZA|South Africa", "NG|Nigeria", "KE|Kenya", "GH|Ghana",
                "ET|Ethiopia", "UG|Uganda", "TZ|Tanzania", "RW|Rwanda", "MA|Morocco", "TN|Tunisia", "SN|Senegal",
                "CM|Cameroon", "IN|India", "PK|Pakistan", "BD|Bangladesh", "LK|Sri Lanka", "SG|Singapore",
                "MY|Malaysia", "ID|Indonesia", "PH|Philippines", "TH|Thailand", "VN|Vietnam;Viet Nam", "JP|Japan",
                "KR|South Korea;Korea;Republic of Korea", "CN|China", "HK|Hong Kong", "TW|Taiwan", "BR|Brazil;Brasil",
                "AR|Argentina", "MX|Mexico", "CO|Colombia", "CL|Chile", "PE|Peru", "UY|Uruguay", "LT|Lithuania",
                "LV|Latvia", "EE|Estonia", "HU|Hungary", "BG|Bulgaria", "HR|Croatia", "RS|Serbia", "SK|Slovakia",
                "SI|Slovenia", "LU|Luxembourg", "IS|Iceland", "CY|Cyprus", "MT|Malta", "QA|Qatar", "KW|Kuwait",
                "JO|Jordan", "CR|Costa Rica", "PA|Panama", "DO|Dominican Republic", "EC|Ecuador" };
        for (String entry : countries) {
            String[] parts = entry.split("\\|");
            COUNTRY_CODES.add(parts[0]);
            for (String name : parts[1].split(";")) {
                COUNTRY_BY_NAME.put(key(name), parts[0]);
            }
        }
        String[] states = {
                "AL=Alabama", "AK=Alaska", "AZ=Arizona", "AR=Arkansas", "CA=California", "CO=Colorado",
                "CT=Connecticut", "DE=Delaware", "DC=District of Columbia", "FL=Florida", "GA=Georgia", "HI=Hawaii",
                "ID=Idaho", "IL=Illinois", "IN=Indiana", "IA=Iowa", "KS=Kansas", "KY=Kentucky", "LA=Louisiana",
                "ME=Maine", "MD=Maryland", "MA=Massachusetts", "MI=Michigan", "MN=Minnesota", "MS=Mississippi",
                "MO=Missouri", "MT=Montana", "NE=Nebraska", "NV=Nevada", "NH=New Hampshire", "NJ=New Jersey",
                "NM=New Mexico", "NY=New York State", "NC=North Carolina", "ND=North Dakota", "OH=Ohio",
                "OK=Oklahoma", "OR=Oregon", "PA=Pennsylvania", "RI=Rhode Island", "SC=South Carolina",
                "SD=South Dakota", "TN=Tennessee", "TX=Texas", "UT=Utah", "VT=Vermont", "VA=Virginia",
                "WA=Washington", "WV=West Virginia", "WI=Wisconsin", "WY=Wyoming", "PR=Puerto Rico" };
        for (String entry : states) {
            String[] parts = entry.split("=");
            US_STATES.put(key(parts[0]), parts[0]);
            US_STATES.put(key(parts[1]), parts[0]);
        }
        String[] provinces = {
                "AB=Alberta", "BC=British Columbia", "MB=Manitoba", "NB=New Brunswick", "NS=Nova Scotia",
                "ON=Ontario", "QC=Quebec", "NT=Northwest Territories", "NU=Nunavut", "YT=Yukon",
                "NL=Newfoundland and Labrador", "PE=Prince Edward Island", "SK=Saskatchewan" };
        for (String entry : provinces) {
            String[] parts = entry.split("=");
            // NL, PE and SK are also country codes (Netherlands, Peru, Slovakia): only the full name is a province.
            if (!Set.of("NL", "PE", "SK").contains(parts[0])) {
                CA_PROVINCES.put(key(parts[0]), parts[0]);
            }
            CA_PROVINCES.put(key(parts[1]), parts[0]);
        }
        String[] cities = {
                "Lagos|NG", "Abuja|NG", "Port Harcourt|NG", "Ibadan|NG", "Nairobi|KE", "Accra|GH", "Kigali|RW",
                "Cape Town|ZA", "Johannesburg|ZA", "Cairo|EG", "London|GB", "Manchester|GB", "Edinburgh|GB",
                "Berlin|DE", "Munich|DE", "Munchen|DE:Munich", "Hamburg|DE", "Paris|FR", "Amsterdam|NL", "Dublin|IE",
                "Madrid|ES", "Barcelona|ES", "Lisbon|PT", "Bengaluru|IN", "Bangalore|IN:Bengaluru", "Mumbai|IN",
                "Delhi|IN", "Hyderabad|IN", "Singapore|SG", "Sydney|AU", "Melbourne|AU", "Tokyo|JP", "Tel Aviv|IL",
                "Dubai|AE", "Stockholm|SE", "Zurich|CH", "Sao Paulo|BR", "Toronto|CA", "New York|US",
                "New York City|US:New York", "NYC|US:New York", "San Francisco|US", "Seattle|US", "Boston|US",
                "Chicago|US", "Austin|US", "Los Angeles|US", "Denver|US" };
        for (String entry : cities) {
            String[] parts = entry.split("\\|");
            String[] target = parts[1].split(":");
            String display = target.length > 1 ? target[1] : parts[0];
            CITIES.put(key(parts[0]), new String[] { display, target[0] });
        }
    }

    private LocationParser() {
    }

    static Parsed parse(String raw) {
        String text = TextCleaner.line(raw);
        if (text == null) {
            return Parsed.NONE;
        }
        boolean hybrid = HYBRID.matcher(text).find();
        boolean remote = REMOTE.matcher(text).find();
        boolean onsite = ONSITE.matcher(text).find();

        String cleaned = ZIP.matcher(FILLER.matcher(text.replaceAll("\\d{2,3}%", " ")).replaceAll(" ")).replaceAll(" ");
        cleaned = EMPTY_BRACKETS.matcher(cleaned).replaceAll(" ");
        for (String segment : SEGMENT_SPLIT.split(cleaned)) {
            String[] place = place(segment);
            if (place != null) {
                return new Parsed(place[0], place[1], remote, hybrid, onsite);
            }
        }
        return new Parsed(null, null, remote, hybrid, onsite);
    }

    /** {city, country} for one segment, either of which may be null; null if the segment names nothing. */
    private static String[] place(String segment) {
        List<String> parts = new ArrayList<>();
        for (String part : DASH.matcher(segment).replaceAll(", ").split(",")) {
            String cleaned = EDGE_PUNCT.matcher(part).replaceAll("").replaceAll("[()\\[\\]{}]", " ").trim()
                    .replaceAll("\\s+", " ");
            if (!cleaned.isEmpty()) {
                parts.add(cleaned);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        String country = null;
        String last = parts.get(parts.size() - 1);
        String firstCityCountry = CITIES.containsKey(key(parts.get(0))) ? CITIES.get(key(parts.get(0)))[1] : null;

        if (parts.size() == 1) {
            String[] known = CITIES.get(key(last));
            if (known != null) {
                return new String[] { known[0], known[1] };
            }
        }
        if (isRegion(last)) {
            parts.remove(parts.size() - 1);
        } else {
            country = countryOf(last, parts.size() > 1, firstCityCountry);
            if (country != null) {
                parts.remove(parts.size() - 1);
                // "Austin, TX, United States": the state sat before the country.
                if (!parts.isEmpty() && (country.equals("US") || country.equals("CA"))) {
                    String region = parts.get(parts.size() - 1);
                    if (stateCountry(region) != null && parts.size() > 1) {
                        parts.remove(parts.size() - 1);
                    }
                }
            }
        }
        if (parts.isEmpty()) {
            return new String[] { null, country };
        }
        String city = parts.get(0);
        if (isRegion(city) || (country == null && stateCountry(city) != null && parts.size() == 1)) {
            String fromState = country == null ? stateCountry(city) : null;
            return new String[] { null, fromState != null ? fromState : country };
        }
        String[] known = CITIES.get(key(city));
        if (known != null) {
            return new String[] { known[0], country != null ? country : known[1] };
        }
        return new String[] { display(city), country };
    }

    private static boolean isRegion(String text) {
        return REGIONS.contains(key(text));
    }

    /** The country a trailing part names, resolving two-letter codes that are also US states. */
    private static String countryOf(String part, boolean hasCity, String cityCountry) {
        String key = key(part);
        boolean code = part.length() == 2 && part.equals(part.toUpperCase(Locale.ROOT));
        if (code && hasCity) {
            // A Canadian province beats a city's usual country ("London, ON"); a city's country beats a
            // US state that shares the code ("Berlin, DE").
            if (CA_PROVINCES.containsKey(key)) {
                return "CA";
            }
            if (cityCountry != null) {
                return cityCountry;
            }
            if (US_STATES.containsKey(key)) {
                return "US";
            }
        }
        String named = COUNTRY_BY_NAME.get(key);
        if (named != null) {
            return named;
        }
        if (code && COUNTRY_CODES.contains(part)) {
            return part;
        }
        String state = stateCountry(part);
        return state;
    }

    private static String stateCountry(String part) {
        String key = key(part);
        if (US_STATES.containsKey(key)) {
            return "US";
        }
        if (CA_PROVINCES.containsKey(key)) {
            return "CA";
        }
        return null;
    }

    private static String display(String city) {
        boolean allUpper = city.equals(city.toUpperCase(Locale.ROOT));
        boolean allLower = city.equals(city.toLowerCase(Locale.ROOT));
        if (!allUpper && !allLower) {
            return city;
        }
        StringBuilder out = new StringBuilder();
        for (String word : city.toLowerCase(Locale.ROOT).split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String key(String text) {
        return TextCleaner.fold(text).replaceAll("[.']", "").replaceAll("\\s+", " ").trim();
    }
}
