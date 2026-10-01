package com.jobfinder.core.ingestion.internal.ats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.FetchTarget;
import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;
import com.jobfinder.core.ingestion.SourceFetchException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SmartRecruiters Posting API: {@code GET /v1/companies/{id}/postings?limit=100&offset=} lists postings
 * without a description, so each posting's detail ({@code .../postings/{postingId}}) is fetched for the
 * job ad. That is one request per posting, so it is bounded ({@code smartRecruitersMaxDetails}) and paced
 * ({@code smartRecruitersDetailDelay}, under the documented 10 requests per second). A posting past the
 * bound, or whose detail has gone (404), is stored from its list entry, with no description. No key.
 * See ADR 0020.
 */
@Component
class SmartRecruitersAdapter extends AtsAdapter {

    static final String CODE = "SMARTRECRUITERS";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;
    private static final String[] SECTIONS = { "companyDescription", "jobDescription", "qualifications",
            "additionalInformation" };

    private final AtsHttp http;
    private final String baseUrl;
    private final int maxDetails;
    private final Duration detailDelay;

    SmartRecruitersAdapter(AtsHttp http, AtsProperties properties, JsonMapper json) {
        super(CODE, json);
        this.http = http;
        this.baseUrl = properties.smartRecruitersBaseUrl();
        this.maxDetails = properties.smartRecruitersMaxDetails();
        this.detailDelay = properties.smartRecruitersDetailDelay();
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        String company = AtsHttp.token(CODE, target.identifier());
        List<JsonNode> listed = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            JsonNode body = http.getJson("SmartRecruiters", company, baseUrl + "/v1/companies/" + company
                    + "/postings?limit=" + PAGE_SIZE + "&offset=" + offset);
            JsonNode content = requireArray(body.get("content"), "postings", "SmartRecruiters", company);
            content.forEach(listed::add);
            JsonNode total = body.get("totalFound");
            if (content.isEmpty() || content.size() < PAGE_SIZE
                    || (total != null && total.isNumber() && offset + content.size() >= total.longValue())) {
                break;
            }
        }
        List<RawPosting> postings = new ArrayList<>();
        int detailed = 0;
        for (JsonNode item : listed) {
            String id = Fields.text(item, "id");
            if (id == null) {
                continue;
            }
            JsonNode stored = item;
            if (detailed < maxDetails) {
                if (detailed > 0) {
                    pause();
                }
                detailed++;
                JsonNode detail = detail(company, id);
                if (detail != null) {
                    stored = detail;
                }
            }
            postings.add(raw(id, stored));
        }
        return postings.stream();
    }

    /** The posting's detail, or null when it is gone or unreadable; a transient failure still fails the fetch. */
    private JsonNode detail(String company, String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) {
            return null;
        }
        try {
            JsonNode detail = http.getJson("SmartRecruiters", company,
                    baseUrl + "/v1/companies/" + company + "/postings/" + id);
            return detail.isObject() ? detail : null;
        } catch (SourceFetchException e) {
            if (e.retryable()) {
                throw e;
            }
            return null;
        }
    }

    private void pause() {
        if (detailDelay.isZero() || detailDelay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(detailDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.transientFailure("SmartRecruiters fetch was interrupted", e);
        }
    }

    @Override
    public Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        JsonNode item = read(posting);
        String title = Fields.text(item, "name");
        if (title == null) {
            throw new IllegalArgumentException("SmartRecruiters posting has no title");
        }
        JsonNode location = Fields.child(item, "location");
        String text = Fields.text(location, "fullLocation");
        if (text == null) {
            text = Fields.join(Fields.text(location, "city"), Fields.text(location, "region"),
                    Fields.text(location, "country"));
        }
        Boolean remote = Fields.bool(location, "remote");
        if (Boolean.TRUE.equals(Fields.bool(location, "hybrid"))) {
            text = text == null ? "Hybrid" : text + " (Hybrid)";
            remote = null;
        }
        return Optional.of(NormalizerInput.builder(title)
                .companyName(Fields.text(Fields.child(item, "company"), "name"))
                .description(description(item))
                .locationText(text)
                .remote(remote)
                .employmentType(Fields.text(Fields.child(item, "typeOfEmployment"), "label"))
                .applyUrl(firstNonNull(Fields.text(item, "postingUrl"), Fields.text(item, "applyUrl")))
                .postedAt(Fields.isoInstant(Fields.text(item, "releasedDate")))
                .build());
    }

    private static String description(JsonNode item) {
        JsonNode sections = Fields.child(Fields.child(item, "jobAd"), "sections");
        if (sections == null) {
            return null;
        }
        StringBuilder html = new StringBuilder();
        for (String name : SECTIONS) {
            JsonNode section = Fields.child(sections, name);
            String body = Fields.text(section, "text");
            if (body != null) {
                String heading = Fields.text(section, "title");
                if (heading != null) {
                    html.append("<h3>").append(heading.replace("&", "&amp;").replace("<", "&lt;")).append("</h3>");
                }
                html.append(body);
            }
        }
        return html.isEmpty() ? null : html.toString();
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
