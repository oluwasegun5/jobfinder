package com.jobfinder.core.ingestion.internal.aggregator;

import java.time.Instant;
import java.util.LinkedHashMap;
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
 * Arbeitnow job board API: {@code GET /api/job-board-api?page=N}, newest first, no key, several hundred jobs
 * (about 2.5 MB) a page, refreshed hourly; {@code links.next} is null on the last page. The only target is
 * {@code all}. At most {@code maxPagesPerTarget} pages a run. The jobs are mostly German and European,
 * collected from employers' ATS boards. See ADR 0021.
 */
@Component
class ArbeitnowAdapter extends AggregatorAdapter {

    static final String CODE = "ARBEITNOW";

    static final SourceAttribution ATTRIBUTION = new SourceAttribution("Arbeitnow", "Arbeitnow",
            "https://www.arbeitnow.com",
            "A link back to Arbeitnow.com on the platform is required by its API terms ('please do not abuse'); "
                    + "the API can be revoked at any time. Terms: arbeitnow.com/terms");

    private final AggregatorProperties.Arbeitnow config;

    ArbeitnowAdapter(AggregatorHttp http, AggregatorProperties properties, JsonMapper json) {
        super(CODE, "Arbeitnow", json, http, properties.arbeitnow().maxRequestsPerDay(),
                properties.arbeitnow().minRequestInterval(), ATTRIBUTION);
        this.config = properties.arbeitnow();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        if (!"all".equals(target.identifier())) {
            throw SourceFetchException.permanentFailure("Arbeitnow target is not valid (the only target is 'all')", null);
        }
        Map<String, RawPosting> postings = collector();
        for (int page = 1; page <= config.maxPagesPerTarget(); page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page", String.valueOf(page));
            JsonNode body = request("all", uri(config.baseUrl(), "/api/job-board-api", query), Map.of(), page == 1);
            if (body == null) {
                break;
            }
            JsonNode data = requireArray(body.get("data"), "data", "all");
            for (JsonNode item : data) {
                String slug = Fields.text(item, "slug");
                if (slug != null) {
                    add(postings, raw(slug, item));
                }
            }
            JsonNode next = Fields.child(Fields.child(body, "links"), "next");
            if (data.isEmpty() || next == null || next.isString() && Fields.scalar(next) == null) {
                break;
            }
        }
        return postings.values().stream();
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode item = read(posting);
        String title = Fields.text(item, "title");
        if (title == null) {
            throw new IllegalArgumentException("Arbeitnow posting has no title");
        }
        JsonNode types = Fields.child(item, "job_types");
        String employment = types != null && types.isArray() && !types.isEmpty() ? Fields.scalar(types.get(0)) : null;
        return Optional.of(NormalizerInput.builder(title)
                .companyName(Fields.text(item, "company_name"))
                .description(Fields.text(item, "description"))
                .locationText(Fields.text(item, "location"))
                .remote(Fields.bool(item, "remote"))
                .employmentType(employment)
                .applyUrl(Fields.text(item, "url"))
                .postedAt(Fields.epochSeconds(item, "created_at"))
                .build());
    }
}
