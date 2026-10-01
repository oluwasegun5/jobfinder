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
 * Ashby public job posting API: {@code GET /posting-api/job-board/{name}?includeCompensation=true}, one
 * response with every listed job. Unlisted jobs are dropped here: they are not on the board, so their
 * absence must count towards expiry. No key. See ADR 0020.
 */
@Component
class AshbyAdapter extends AtsAdapter {

    static final String CODE = "ASHBY";

    private final AtsHttp http;
    private final String baseUrl;

    AshbyAdapter(AtsHttp http, AtsProperties properties, JsonMapper json) {
        super(CODE, json);
        this.http = http;
        this.baseUrl = properties.ashbyBaseUrl();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        String name = AtsHttp.token(CODE, target.identifier());
        JsonNode body = http.getJson("Ashby", name, baseUrl + "/posting-api/job-board/" + name
                + "?includeCompensation=true");
        JsonNode jobs = requireArray(body.get("jobs"), "jobs", "Ashby", name);
        List<RawPosting> postings = new ArrayList<>();
        for (JsonNode job : jobs) {
            String id = Fields.text(job, "id");
            if (id != null && !Boolean.FALSE.equals(Fields.bool(job, "isListed"))) {
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
            throw new IllegalArgumentException("Ashby posting has no title");
        }
        String workplace = Fields.text(job, "workplaceType");
        String location = Fields.text(job, "location");
        Boolean remote = Fields.bool(job, "isRemote");
        if ("hybrid".equalsIgnoreCase(workplace)) {
            // The flag is yes/no; hybrid travels in the location text, where the normalizer reads it.
            location = location == null ? "Hybrid" : location + " (Hybrid)";
            remote = null;
        } else if (remote == null && "remote".equalsIgnoreCase(workplace)) {
            remote = true;
        }
        String description = Fields.text(job, "descriptionHtml");
        NormalizerInput.Builder input = NormalizerInput.builder(title)
                .description(description != null ? description : Fields.text(job, "descriptionPlain"))
                .locationText(location)
                .remote(remote)
                .employmentType(Fields.text(job, "employmentType"))
                .applyUrl(firstNonNull(Fields.text(job, "jobUrl"), Fields.text(job, "applyUrl")))
                .postedAt(Fields.isoInstant(Fields.text(job, "publishedAt")));
        salary(job, input);
        return Optional.of(input.build());
    }

    /** The salary component of the posting's compensation summary, when it has a stated range. */
    private static void salary(JsonNode job, NormalizerInput.Builder input) {
        JsonNode compensation = Fields.child(job, "compensation");
        JsonNode components = Fields.child(compensation, "summaryComponents");
        if (components == null || !components.isArray()) {
            return;
        }
        for (JsonNode component : components) {
            if ("Salary".equalsIgnoreCase(Fields.text(component, "compensationType"))
                    && (Fields.decimal(component, "minValue") != null || Fields.decimal(component, "maxValue") != null)) {
                input.salary(Fields.decimal(component, "minValue"), Fields.decimal(component, "maxValue"),
                        Fields.text(component, "currencyCode"), Fields.text(component, "interval"));
                return;
            }
        }
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
