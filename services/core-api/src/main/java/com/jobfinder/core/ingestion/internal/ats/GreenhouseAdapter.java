package com.jobfinder.core.ingestion.internal.ats;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.FetchTarget;
import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Greenhouse Job Board API: {@code GET /v1/boards/{token}/jobs?content=true}, one response with every
 * published job (no pagination), HTML description escaped inside a JSON string. No key. See ADR 0020.
 */
@Component
class GreenhouseAdapter extends AtsAdapter {

    static final String CODE = "GREENHOUSE";

    private final AtsHttp http;
    private final String baseUrl;

    GreenhouseAdapter(AtsHttp http, AtsProperties properties, JsonMapper json) {
        super(CODE, json);
        this.http = http;
        this.baseUrl = properties.greenhouseBaseUrl();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        String token = AtsHttp.token(CODE, target.identifier());
        JsonNode body = http.getJson("Greenhouse", token, baseUrl + "/v1/boards/" + token + "/jobs?content=true");
        JsonNode jobs = requireArray(body.get("jobs"), "jobs", "Greenhouse", token);
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = Fields.text(job, "id");
            if (id != null) {
                postings.add(raw(id, job));
            }
        }
        return postings.stream();
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode job = read(posting);
        String title = Fields.text(job, "title");
        if (title == null) {
            throw new IllegalArgumentException("Greenhouse posting has no title");
        }
        NormalizerInput.Builder input = NormalizerInput.builder(title)
                .companyName(Fields.text(job, "company_name"))
                .description(Fields.text(job, "content"))
                .locationText(Fields.text(Fields.child(job, "location"), "name"))
                .applyUrl(Fields.text(job, "absolute_url"))
                .postedAt(Fields.isoInstant(Fields.text(job, "first_published")))
                .expiresAt(Fields.isoInstant(Fields.text(job, "application_deadline")));
        payRange(job, input);
        return Optional.of(input.build());
    }

    /** Pay transparency ranges, when the board publishes them: amounts are in cents, the period is not stated. */
    private static void payRange(JsonNode job, NormalizerInput.Builder input) {
        JsonNode ranges = Fields.child(job, "pay_input_ranges");
        if (ranges == null || !ranges.isArray()) {
            return;
        }
        for (JsonNode range : ranges) {
            BigDecimal min = Fields.decimal(range, "min_cents");
            BigDecimal max = Fields.decimal(range, "max_cents");
            String currency = Fields.text(range, "currency_type");
            if ((min != null || max != null) && currency != null) {
                BigDecimal hundred = BigDecimal.valueOf(100);
                input.salary(min == null ? null : min.divide(hundred), max == null ? null : max.divide(hundred),
                        currency, null);
                return;
            }
        }
    }
}
