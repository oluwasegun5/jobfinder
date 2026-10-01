package com.jobfinder.core.ingestion.internal.aggregator;

import java.time.Instant;
import java.math.BigDecimal;
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
 * Remote OK: {@code GET /api}, a bare JSON array whose first element is the legal notice (the API
 * terms) and the rest are the latest jobs; no key, no pages. The only target is {@code all}: the {@code tag} parameter some clients mention did not filter when tried (ADR 0021). Every job is
 * remote; the salary, where given (0 means none), is annual USD. The job's own Remote OK URL is the apply link,
 * as the terms require linking to it. See ADR 0021.
 */
@Component
class RemoteOkAdapter extends AggregatorAdapter {

    static final String CODE = "REMOTEOK";

    static final SourceAttribution ATTRIBUTION = new SourceAttribution("Remote OK", "Remote OK",
            "https://remoteok.com",
            "Link each job to its Remote OK URL (stored as the listing URL) with a followed link (no nofollow) and "
                    + "mention Remote OK as the source, or API access is suspended. Do not use the Remote OK logo "
                    + "without written permission; use the name. The terms are the first element of the API response.");

    private final AggregatorProperties.RemoteOk config;

    RemoteOkAdapter(AggregatorHttp http, AggregatorProperties properties, JsonMapper json) {
        super(CODE, "Remote OK", json, http, properties.remoteok().maxRequestsPerDay(),
                properties.remoteok().minRequestInterval(), ATTRIBUTION);
        this.config = properties.remoteok();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        if (!"all".equals(target.identifier())) {
            throw SourceFetchException.permanentFailure("Remote OK target is not valid (the only target is 'all')", null);
        }
        JsonNode body = request("all", uri(config.baseUrl(), "/api", Map.of()), Map.of(), true);
        requireArray(body, "postings", "all");
        Map<String, RawPosting> postings = collector();
        for (JsonNode item : body) {
            // The legal notice has no id or position: it is not a job.
            String id = Fields.text(item, "id");
            if (id != null && Fields.text(item, "position") != null) {
                add(postings, raw(id, item));
            }
        }
        return postings.values().stream();
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode item = read(posting);
        String title = Fields.text(item, "position");
        if (title == null) {
            throw new IllegalArgumentException("Remote OK posting has no position");
        }
        NormalizerInput.Builder input = NormalizerInput.builder(title)
                .companyName(Fields.text(item, "company"))
                .description(Fields.text(item, "description"))
                .locationText(Fields.text(item, "location"))
                .remote(true)
                .applyUrl(firstNonNull(Fields.text(item, "url"), Fields.text(item, "apply_url")))
                .postedAt(postedAt(item));
        BigDecimal min = Fields.decimal(item, "salary_min");
        BigDecimal max = Fields.decimal(item, "salary_max");
        boolean hasMin = min != null && min.signum() > 0;
        boolean hasMax = max != null && max.signum() > 0;
        if (hasMin || hasMax) {
            input.salary(hasMin ? min : null, hasMax ? max : null, "USD", "year");
        }
        return Optional.of(input.build());
    }

    private static Instant postedAt(JsonNode item) {
        Instant epoch = Fields.epochSeconds(item, "epoch");
        return epoch != null ? epoch : Fields.isoInstant(Fields.text(item, "date"));
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
