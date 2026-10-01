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
import com.jobfinder.core.ingestion.internal.ats.Fields;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Remotive: {@code GET /api/remote-jobs[?category=slug]}, one response with everything (no pages), no key.
 * A target is {@code all} or a category slug such as {@code software-dev}. Remotive advises at most 4 requests a
 * day and blocks more than 2 a minute, and its jobs are delayed by 24 hours on purpose. Every job is remote.
 * The job's own Remotive URL is the apply link: Remotive's terms require linking back to it. See ADR 0021.
 */
@Component
class RemotiveAdapter extends AggregatorAdapter {

    static final String CODE = "REMOTIVE";

    static final SourceAttribution ATTRIBUTION = new SourceAttribution("Remotive", "Remotive",
            "https://remotive.com",
            "Link each job to its Remotive URL (stored as the listing URL) and mention Remotive as the source. Do not "
                    + "submit or repost Remotive jobs to other job sites or aggregators (Google Jobs, LinkedIn, Jooble...), "
                    + "and do not show them to collect sign-ups or email addresses. The feed is delayed 24 hours. Breach "
                    + "ends API access. Terms: remotive.com/api-documentation");

    private final AggregatorProperties.Remotive config;

    RemotiveAdapter(AggregatorHttp http, AggregatorProperties properties, JsonMapper json) {
        super(CODE, "Remotive", json, http, properties.remotive().maxRequestsPerDay(),
                properties.remotive().minRequestInterval(), ATTRIBUTION);
        this.config = properties.remotive();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        String category = slug(target.identifier());
        Map<String, String> query = new LinkedHashMap<>();
        if (!category.equals("all")) {
            query.put("category", category);
        }
        JsonNode body = request(category, uri(config.baseUrl(), "/api/remote-jobs", query), Map.of(), true);
        JsonNode jobs = requireArray(body.get("jobs"), "jobs", category);
        Map<String, RawPosting> postings = collector();
        for (JsonNode item : jobs) {
            String id = Fields.text(item, "id");
            if (id != null) {
                add(postings, raw(id, item));
            }
        }
        return postings.values().stream();
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode item = read(posting);
        String title = Fields.text(item, "title");
        if (title == null) {
            throw new IllegalArgumentException("Remotive posting has no title");
        }
        return Optional.of(NormalizerInput.builder(title)
                .companyName(Fields.text(item, "company_name"))
                .description(Fields.text(item, "description"))
                .locationText(Fields.text(item, "candidate_required_location"))
                .remote(true)
                .employmentType(Fields.text(item, "job_type"))
                .salaryText(Fields.text(item, "salary"))
                .applyUrl(Fields.text(item, "url"))
                // "2026-09-21T12:55:11": no offset is given; the feed is UTC.
                .postedAt(Fields.utcInstant(Fields.text(item, "publication_date")))
                .build());
    }
}
