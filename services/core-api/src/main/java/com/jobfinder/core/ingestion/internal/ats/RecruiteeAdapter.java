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
 * Recruitee careers-site API: {@code GET https://{company}.recruitee.com/api/offers/}, one response with
 * every published offer, descriptions included. The company is the host name, so the token is validated as
 * a DNS label before it is used. No key. See ADR 0020.
 */
@Component
class RecruiteeAdapter extends AtsAdapter {

    static final String CODE = "RECRUITEE";

    private final AtsHttp http;
    private final String baseUrl;

    RecruiteeAdapter(AtsHttp http, AtsProperties properties, JsonMapper json) {
        super(CODE, json);
        this.http = http;
        this.baseUrl = properties.recruiteeBaseUrl();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        String company = AtsHttp.hostLabel(CODE, target.identifier());
        JsonNode body = http.getJson("Recruitee", company, baseUrl.replace("{token}", company) + "/api/offers/");
        JsonNode offers = requireArray(body.get("offers"), "offers", "Recruitee", company);
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode offer : offers) {
            String id = Fields.text(offer, "id");
            String status = Fields.text(offer, "status");
            if (id != null && (status == null || "published".equalsIgnoreCase(status))) {
                postings.add(raw(id, offer));
            }
        }
        return postings.stream();
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode offer = read(posting);
        String title = Fields.text(offer, "title");
        if (title == null) {
            throw new IllegalArgumentException("Recruitee posting has no title");
        }
        String location = Fields.text(offer, "location");
        if (location == null) {
            location = Fields.join(Fields.text(offer, "city"), Fields.text(offer, "country"));
        }
        Boolean remote = Boolean.TRUE.equals(Fields.bool(offer, "remote")) ? Boolean.TRUE
                : Boolean.TRUE.equals(Fields.bool(offer, "on_site")) ? Boolean.FALSE : null;
        if (Boolean.TRUE.equals(Fields.bool(offer, "hybrid"))) {
            location = location == null ? "Hybrid" : location + " (Hybrid)";
            remote = null;
        }
        NormalizerInput.Builder input = NormalizerInput.builder(title)
                .companyName(Fields.text(offer, "company_name"))
                .description(description(offer))
                .locationText(location)
                .remote(remote)
                .employmentType(Fields.text(offer, "employment_type_code"))
                .applyUrl(Fields.text(offer, "careers_url"))
                .postedAt(Fields.recruiteeInstant(Fields.text(offer, "published_at")))
                .expiresAt(Fields.recruiteeInstant(Fields.text(offer, "close_at")));
        JsonNode salary = Fields.child(offer, "salary");
        if (salary != null) {
            input.salary(Fields.decimal(salary, "min"), Fields.decimal(salary, "max"), Fields.text(salary, "currency"),
                    Fields.text(salary, "period"));
        }
        return Optional.of(input.build());
    }

    private static String description(JsonNode offer) {
        String body = Fields.text(offer, "description");
        String requirements = Fields.text(offer, "requirements");
        if (body == null) {
            return requirements;
        }
        return requirements == null ? body : body + "<h3>Requirements</h3>" + requirements;
    }
}
