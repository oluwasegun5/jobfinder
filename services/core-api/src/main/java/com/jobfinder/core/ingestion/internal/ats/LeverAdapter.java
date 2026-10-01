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
 * Lever Postings API: {@code GET /v0/postings/{company}?mode=json&skip=&limit=}, a bare JSON array,
 * paged until a short page. The description is split over {@code description}, {@code lists} and
 * {@code additional}, which are put back together here. No key. See ADR 0020.
 */
@Component
class LeverAdapter extends AtsAdapter {

    static final String CODE = "LEVER";
    /** Safety stop: 200 pages of 100 postings is far beyond any real board. */
    private static final int MAX_PAGES = 200;

    private final AtsHttp http;
    private final String baseUrl;
    private final int pageSize;

    LeverAdapter(AtsHttp http, AtsProperties properties, JsonMapper json) {
        super(CODE, json);
        this.http = http;
        this.baseUrl = properties.leverBaseUrl();
        this.pageSize = properties.leverPageSize();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        String company = AtsHttp.token(CODE, target.identifier());
        List<RawPosting> postings = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = http.getJson("Lever", company, baseUrl + "/v0/postings/" + company
                    + "?mode=json&skip=" + (page * pageSize) + "&limit=" + pageSize);
            JsonNode items = requireArray(body, "postings", "Lever", company);
            for (JsonNode item : items) {
                String id = Fields.text(item, "id");
                if (id != null) {
                    postings.add(raw(id, item));
                }
            }
            if (items.size() < pageSize) {
                break;
            }
        }
        return postings.stream();
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode item = read(posting);
        String title = Fields.text(item, "text");
        if (title == null) {
            throw new IllegalArgumentException("Lever posting has no title");
        }
        JsonNode categories = Fields.child(item, "categories");
        String workplace = Fields.text(item, "workplaceType");
        String location = Fields.text(categories, "location");
        if (location == null) {
            JsonNode all = Fields.child(categories, "allLocations");
            location = all != null && all.isArray() && !all.isEmpty() ? Fields.scalar(all.get(0)) : null;
        }
        Boolean remote = null;
        if ("remote".equalsIgnoreCase(workplace)) {
            remote = true;
        } else if ("onsite".equalsIgnoreCase(workplace)) {
            remote = false;
        } else if ("hybrid".equalsIgnoreCase(workplace)) {
            // The flag is yes/no; hybrid travels in the location text, where the normalizer reads it.
            location = location == null ? "Hybrid" : location + " (Hybrid)";
        }
        NormalizerInput.Builder input = NormalizerInput.builder(title)
                .description(description(item))
                .locationText(location)
                .remote(remote)
                .employmentType(Fields.text(categories, "commitment"))
                .applyUrl(firstNonNull(Fields.text(item, "hostedUrl"), Fields.text(item, "applyUrl")))
                .postedAt(Fields.epochMillis(item, "createdAt"));
        JsonNode salary = Fields.child(item, "salaryRange");
        if (salary != null) {
            input.salary(Fields.decimal(salary, "min"), Fields.decimal(salary, "max"), Fields.text(salary, "currency"),
                    Fields.text(salary, "interval"));
        }
        return Optional.of(input.build());
    }

    private static String description(JsonNode item) {
        StringBuilder html = new StringBuilder();
        String body = Fields.text(item, "description");
        if (body != null) {
            html.append(body);
        }
        JsonNode lists = Fields.child(item, "lists");
        if (lists != null && lists.isArray()) {
            for (JsonNode list : lists) {
                String heading = Fields.text(list, "text");
                String content = Fields.text(list, "content");
                if (content != null) {
                    html.append("<h3>").append(heading == null ? "" : escape(heading)).append("</h3><ul>")
                            .append(content).append("</ul>");
                }
            }
        }
        String additional = Fields.text(item, "additional");
        if (additional != null) {
            html.append(additional);
        }
        if (html.isEmpty()) {
            return Fields.text(item, "descriptionPlain");
        }
        return html.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
