package com.jobfinder.core.ingestion.internal.aggregator;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.FetchTarget;
import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;
import com.jobfinder.core.ingestion.SourceAttribution;
import com.jobfinder.core.ingestion.SourceFetchException;
import com.jobfinder.core.ingestion.internal.ats.Fields;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adzuna Job Search API: {@code GET /v1/api/jobs/{country}/search/{page}} with {@code app_id} and
 * {@code app_key} in the query. A target is {@code country[:what[:where]]}. The newest ads first
 * ({@code sort_by=date}, {@code max_days_old}), at most {@code maxPagesPerTarget} pages of
 * {@code resultsPerPage}. Needs a free key; without one the source is skipped. See ADR 0021.
 *
 * <p>
 * Adzuna's description is a short snippet, and its own salary estimates ("predicted") are not stored: the
 * terms require a separate "Adzuna Jobsworth" label for those, and an estimate is not what the employer said.
 */
@Component
class AdzunaAdapter extends AggregatorAdapter {

    static final String CODE = "ADZUNA";

    static final SourceAttribution ATTRIBUTION = new SourceAttribution("Adzuna", "Jobs by Adzuna",
            "https://www.adzuna.co.uk",
            "Label every displayed ad 'Jobs by Adzuna' (at least 116x23 px), with the word 'Jobs' linking to "
                    + "adzuna.co.uk, and link each ad to its listing URL (Adzuna's redirect_url). Adzuna's own "
                    + "salary estimates are not stored; showing them would need the 'Adzuna Jobsworth' label. "
                    + "Descriptions are Adzuna's short snippets. Terms: developer.adzuna.com/docs/terms_of_service "
                    + "(commercial use beyond a 14-day trial needs a licence agreement).");

    /** Adzuna reports salaries in the currency of the country it serves. */
    private static final Map<String, String> CURRENCY = Map.ofEntries(Map.entry("gb", "GBP"), Map.entry("us", "USD"),
            Map.entry("ca", "CAD"), Map.entry("au", "AUD"), Map.entry("nz", "NZD"), Map.entry("sg", "SGD"),
            Map.entry("za", "ZAR"), Map.entry("in", "INR"), Map.entry("br", "BRL"), Map.entry("mx", "MXN"),
            Map.entry("pl", "PLN"), Map.entry("ch", "CHF"), Map.entry("de", "EUR"), Map.entry("fr", "EUR"),
            Map.entry("nl", "EUR"), Map.entry("at", "EUR"), Map.entry("be", "EUR"), Map.entry("es", "EUR"),
            Map.entry("it", "EUR"));

    private final AggregatorProperties.Adzuna config;

    AdzunaAdapter(AggregatorHttp http, AggregatorProperties properties, JsonMapper json) {
        super(CODE, "Adzuna", json, http, properties.adzuna().maxRequestsPerDay(),
                properties.adzuna().minRequestInterval(), ATTRIBUTION);
        this.config = properties.adzuna();
    }

    @Override
    public Optional<String> unavailableReason() {
        return config.configured() ? Optional.empty()
                : Optional.of("Adzuna credentials are not configured (set ADZUNA_APP_ID and ADZUNA_APP_KEY)");
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        if (!config.configured()) {
            throw SourceFetchException.permanentFailure("Adzuna credentials are not configured", null);
        }
        SearchTarget search = SearchTarget.parse(displayName(), target.identifier(), false);
        String label = search.country();
        Map<String, RawPosting> postings = collector();
        for (int page = 1; page <= config.maxPagesPerTarget(); page++) {
            JsonNode body = request(label, pageUri(search, page), Map.of(), page == 1);
            if (body == null) {
                break;
            }
            JsonNode results = requireArray(body.get("results"), "results", label);
            for (JsonNode item : results) {
                String id = Fields.text(item, "id");
                if (id != null) {
                    add(postings, raw(id, item));
                }
            }
            if (results.size() < config.resultsPerPage()) {
                break;
            }
        }
        return postings.values().stream();
    }

    private URI pageUri(SearchTarget search, int page) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("app_id", config.appId());
        query.put("app_key", config.appKey());
        query.put("results_per_page", String.valueOf(config.resultsPerPage()));
        query.put("what", search.query());
        query.put("where", search.where());
        query.put("max_days_old", String.valueOf(config.maxDaysOld()));
        query.put("sort_by", "date");
        query.put("content-type", "application/json");
        return uri(config.baseUrl(), "/v1/api/jobs/" + search.country() + "/search/" + page, query);
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode item = read(posting);
        String title = Fields.text(item, "title");
        if (title == null) {
            throw new IllegalArgumentException("Adzuna posting has no title");
        }
        SearchTarget search;
        try {
            search = SearchTarget.parse(displayName(), target.identifier(), false);
        } catch (SourceFetchException e) {
            throw new IllegalArgumentException("Adzuna target is not a search", e);
        }
        String country = search.country();
        String location = Fields.text(Fields.child(item, "location"), "display_name");
        String locationText = location == null ? country.toUpperCase(Locale.ROOT) : location + ", " + country.toUpperCase(Locale.ROOT);
        String contractType = Fields.text(item, "contract_type");
        String employment = "contract".equalsIgnoreCase(contractType) ? "contract" : Fields.text(item, "contract_time");

        NormalizerInput.Builder input = NormalizerInput.builder(title)
                .companyName(Fields.text(Fields.child(item, "company"), "display_name"))
                .description(Fields.text(item, "description"))
                .locationText(locationText)
                .employmentType(employment)
                .applyUrl(Fields.text(item, "redirect_url"))
                .postedAt(Fields.isoInstant(Fields.text(item, "created")));
        if (!predicted(item)) {
            // The period is not stated (the normalizer infers a year only where the size leaves no doubt).
            input.salary(Fields.decimal(item, "salary_min"), Fields.decimal(item, "salary_max"), CURRENCY.get(country),
                    null);
        }
        return Optional.of(input.build());
    }

    /** {@code salary_is_predicted} is "1" (or true) when the figure is Adzuna's estimate, not the advertiser's. */
    private static boolean predicted(JsonNode item) {
        JsonNode flag = Fields.child(item, "salary_is_predicted");
        if (flag == null) {
            return true; // not stated: not known to be the advertiser's own figure, so not stored
        }
        if (flag.isBoolean()) {
            return flag.booleanValue();
        }
        String text = Fields.scalar(flag);
        return text != null && !text.equals("0");
    }
}
