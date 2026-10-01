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
 * JSearch on RapidAPI (Google for Jobs, so it reaches listings of LinkedIn, Indeed and others without scraping
 * them, ADR 0003 and 0004): {@code GET /search?query=&page=&num_pages=1&date_posted=&country=} with the
 * {@code X-RapidAPI-Key} and {@code X-RapidAPI-Host} headers. A target is {@code country:query}, for example
 * {@code us:remote software engineer jobs}. One request per page (each costs one of the plan's requests), so
 * {@code maxPagesPerTarget} defaults to 1. Needs a RapidAPI key; without one the source is skipped. See ADR 0021.
 *
 * <p>
 * The response fields below are those of the JSearch documentation as summarized by third parties and its
 * RapidAPI listing; they could not be called live without a key, so the mapping reads every field defensively
 * and the tests use a synthetic fixture.
 */
@Component
class JSearchAdapter extends AggregatorAdapter {

    static final String CODE = "JSEARCH";

    static final SourceAttribution ATTRIBUTION = new SourceAttribution("JSearch", "Job data via JSearch (Google for Jobs)",
            "https://rapidapi.com/letscrape-6bRBa3QguO5/api/jsearch",
            "No attribution wording was found in JSearch's public documentation. Each job's apply link goes to the "
                    + "publisher that posted it (LinkedIn, Indeed, ...); its name is kept as job_publisher in the raw "
                    + "posting, and showing it next to the job is recommended. RapidAPI's terms apply.");

    private final AggregatorProperties.JSearch config;

    JSearchAdapter(AggregatorHttp http, AggregatorProperties properties, JsonMapper json) {
        super(CODE, "JSearch", json, http, properties.jsearch().maxRequestsPerDay(),
                properties.jsearch().minRequestInterval(), ATTRIBUTION);
        this.config = properties.jsearch();
    }

    @Override
    public Optional<String> unavailableReason() {
        return config.configured() ? Optional.empty()
                : Optional.of("JSearch credentials are not configured (set JSEARCH_RAPIDAPI_KEY)");
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        if (!config.configured()) {
            throw SourceFetchException.permanentFailure("JSearch credentials are not configured", null);
        }
        SearchTarget search = SearchTarget.parse(displayName(), target.identifier(), true);
        String label = search.country();
        Map<String, String> headers = Map.of("X-RapidAPI-Key", config.apiKey(), "X-RapidAPI-Host", config.apiHost());
        Map<String, RawPosting> postings = collector();
        for (int page = 1; page <= config.maxPagesPerTarget(); page++) {
            JsonNode body = request(label, pageUri(search, page), headers, page == 1);
            if (body == null) {
                break;
            }
            String status = Fields.text(body, "status");
            if (status != null && !"OK".equalsIgnoreCase(status)) {
                throw SourceFetchException.permanentFailure("JSearch answered with status " + status + " for " + label,
                        null);
            }
            JsonNode data = requireArray(body.get("data"), "data", label);
            for (JsonNode item : data) {
                String id = Fields.text(item, "job_id");
                if (id != null) {
                    add(postings, raw(id, item));
                }
            }
            if (data.isEmpty()) {
                break;
            }
        }
        return postings.values().stream();
    }

    private URI pageUri(SearchTarget search, int page) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("query", search.query());
        query.put("page", String.valueOf(page));
        query.put("num_pages", "1");
        query.put("date_posted", config.datePosted());
        query.put("country", search.country());
        return uri(config.baseUrl(), "/search", query);
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode item = read(posting);
        String title = Fields.text(item, "job_title");
        if (title == null) {
            throw new IllegalArgumentException("JSearch posting has no title");
        }
        String location = Fields.text(item, "job_location");
        if (location == null) {
            location = Fields.join(Fields.text(item, "job_city"), Fields.text(item, "job_state"),
                    Fields.text(item, "job_country"));
        }
        NormalizerInput.Builder input = NormalizerInput.builder(title)
                .companyName(Fields.text(item, "employer_name"))
                .description(Fields.text(item, "job_description"))
                .locationText(location)
                .remote(Fields.bool(item, "job_is_remote"))
                .employmentType(employmentLabel(item))
                .applyUrl(Fields.text(item, "job_apply_link"))
                .postedAt(postedAt(item))
                .expiresAt(Fields.isoInstant(Fields.text(item, "job_offer_expiration_datetime_utc")))
                .salary(Fields.decimal(item, "job_min_salary"), Fields.decimal(item, "job_max_salary"),
                        Fields.text(item, "job_salary_currency"), Fields.text(item, "job_salary_period"));
        return Optional.of(input.build());
    }

    private static Instant postedAt(JsonNode item) {
        Instant fromDate = Fields.isoInstant(Fields.text(item, "job_posted_at_datetime_utc"));
        return fromDate != null ? fromDate : Fields.epochSeconds(item, "job_posted_at_timestamp");
    }

    /** JSearch's FULLTIME / PARTTIME / CONTRACTOR / INTERN, as words the normalizer reads. */
    private static String employmentLabel(JsonNode item) {
        String type = Fields.text(item, "job_employment_type");
        if (type == null) {
            JsonNode types = Fields.child(item, "job_employment_types");
            type = types != null && types.isArray() && !types.isEmpty() ? Fields.scalar(types.get(0)) : null;
        }
        if (type == null) {
            return null;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "FULLTIME" -> "Full-time";
            case "PARTTIME" -> "Part-time";
            case "CONTRACTOR" -> "Contract";
            case "INTERN" -> "Internship";
            default -> type;
        };
    }
}
