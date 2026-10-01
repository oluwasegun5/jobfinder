package com.jobfinder.core.ingestion.internal.ats;

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
 * Workable's unauthenticated careers-page endpoint: {@code GET /api/v1/widget/accounts/{subdomain}?details=true}
 * on apply.workable.com (the documented {@code www.workable.com/api/accounts/{subdomain}} redirects to it).
 * One response with every published job and its HTML description. This is not the token-gated {@code /spi/v3}
 * API, which only an account's own users can call. No key. See ADR 0020.
 */
@Component
class WorkableAdapter extends AtsAdapter {

    static final String CODE = "WORKABLE";

    private final AtsHttp http;
    private final String baseUrl;

    WorkableAdapter(AtsHttp http, AtsProperties properties, JsonMapper json) {
        super(CODE, json);
        this.http = http;
        this.baseUrl = properties.workableBaseUrl();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        String subdomain = AtsHttp.token(CODE, target.identifier());
        JsonNode body = http.getJson("Workable", subdomain, baseUrl + "/api/v1/widget/accounts/" + subdomain
                + "?details=true");
        JsonNode jobs = requireArray(body.get("jobs"), "jobs", "Workable", subdomain);
        String company = Fields.text(body, "name");
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = Fields.text(job, "shortcode");
            if (id != null) {
                // The company name is at the top of the response, not in the job; keep it with the posting.
                postings.add(raw(id, company == null || !(job instanceof tools.jackson.databind.node.ObjectNode obj)
                        ? job : obj.deepCopy().put("company_name", company)));
            }
        }
        return postings.stream();
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode job = read(posting);
        String title = Fields.text(job, "title");
        if (title == null) {
            throw new IllegalArgumentException("Workable posting has no title");
        }
        String location = Fields.join(Fields.text(job, "city"), Fields.text(job, "state"), Fields.text(job, "country"));
        Boolean remote = Fields.bool(job, "telecommuting");
        return Optional.of(NormalizerInput.builder(title)
                .companyName(Fields.text(job, "company_name"))
                .description(Fields.text(job, "description"))
                .locationText(location)
                .remote(remote)
                .employmentType(Fields.text(job, "employment_type"))
                .applyUrl(firstNonNull(Fields.text(job, "url"), Fields.text(job, "shortlink")))
                .postedAt(Fields.isoDate(firstNonNull(Fields.text(job, "published_on"), Fields.text(job, "created_at"))))
                .build());
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
