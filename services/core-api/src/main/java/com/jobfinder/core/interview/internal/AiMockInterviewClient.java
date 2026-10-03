package com.jobfinder.core.interview.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiCredits;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;
import com.jobfinder.core.billing.RecordOutcome;
import com.jobfinder.core.interview.MockInterviews.AveragesView;
import com.jobfinder.core.interview.MockInterviews.FeedbackView;
import com.jobfinder.core.interview.MockInterviews.PersonaView;
import com.jobfinder.core.interview.MockInterviews.PointView;
import com.jobfinder.core.interview.MockInterviews.StarView;
import com.jobfinder.core.interview.MockInterviews.SummaryView;
import com.jobfinder.core.jobs.JobForBrief;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls ai-service {@code POST /v1/mock-interview/turn} and {@code /summary} (docs/adr/0034-mock-interview.md) and checks
 * what comes back before anything is stored or shown.
 *
 * <p><b>Metering.</b> Every billed call a response reports goes to the usage ledger under {@code mock_interview} (turns
 * and opening questions) or {@code mock_interview_summary}, {@code SUCCEEDED} when the result was used and
 * {@code FAILED} when it was discarded. The credits those calls cost, by the ledger's own formula
 * ({@link AiCredits}), are added up for the calls the ledger recorded now (a duplicate call id adds nothing) and
 * returned, so the caller can accumulate them on the session: {@code credits_consumed} is never a second price list.
 *
 * <p><b>Second line behind ai-service.</b> Scores must be whole numbers from 1 to 5, STAR must be all present for a
 * behavioural question and all null otherwise (and not score above the components present allow), every strength must
 * carry a quote that is in the answer, every quote must be in the answer, the next question must not repeat an earlier
 * one and, when it is said to come from the prep, must be one of the prep's questions. The summary's averages are
 * computed here from the stored feedback and the service's must agree. A response that fails any check is discarded
 * (usage recorded as FAILED). Answers, job text and prompts are never logged.
 */
@Component
class AiMockInterviewClient {

    static final String TURN_FEATURE = "mock_interview";
    static final String SUMMARY_FEATURE = "mock_interview_summary";
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    /** ai-service could not be reached or could not do the work; {@code credits} are those of calls it billed. */
    static class MockUnavailableException extends RuntimeException {
        private final BigDecimal credits;

        MockUnavailableException(String message, BigDecimal credits) {
            super(message);
            this.credits = credits;
        }

        BigDecimal credits() {
            return credits;
        }
    }

    record PrepQuestion(String category, String question) {
    }

    record AnswerIn(String question, String category, String text) {
    }

    record NextQuestion(String category, String question, String source) {
    }

    /** A checked turn. {@code feedback} is null for an opening question; {@code next} is null on the last turn. */
    record Turn(FeedbackView feedback, NextQuestion next, BigDecimal credits) {
    }

    record SummaryTurnIn(String question, String category, FeedbackView feedback) {
    }

    record Summary(SummaryView summary, BigDecimal credits) {
    }

    /** The placeholder shapes that must never be the text of a stored feedback point, question or step. */
    static final Pattern PLACEHOLDER = Pattern.compile(
            "\\[[^\\]\\n]{1,80}]|\\{\\{|<[A-Za-z][^<>\\n]{0,60}>|NEEDS_INPUT|\\b(?:your|company)\\s+name\\b",
            Pattern.CASE_INSENSITIVE);
    static final Set<String> CATEGORIES = Set.of("behavioral", "technical", "role_specific");

    private static final Logger log = LoggerFactory.getLogger(AiMockInterviewClient.class);

    private final RestClient client;
    private final MockInterviewProperties mock;
    private final InterviewProperties interview;
    private final JsonMapper json;
    private final AiUsageLedger ledger;
    private final AiCredits credits;

    AiMockInterviewClient(InterviewAiProperties ai, MockInterviewProperties mock, InterviewProperties interview,
            JsonMapper json, AiUsageLedger ledger, AiCredits credits) {
        this.mock = mock;
        this.interview = interview;
        this.json = json;
        this.ledger = ledger;
        this.credits = credits;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(mock.connectTimeout()).build());
        factory.setReadTimeout(mock.readTimeout());
        this.client = RestClient.builder().baseUrl(ai.baseUrl()).defaultHeader("X-Service-Token", ai.token())
                .requestFactory(factory).build();
    }

    // --- turn ---

    /**
     * One turn: the feedback on {@code answer} (null to ask only for an opening question) and, when {@code needNext},
     * the next question.
     *
     * @param asked every question asked so far, the one being answered included
     * @param prep  the prep's questions that were not asked yet (empty when the session has no prep)
     */
    Turn turn(UUID userId, PersonaView persona, JobForBrief job, AnswerIn answer, boolean needNext,
            List<String> asked, List<PrepQuestion> prep) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", mock.promptVersion());
        body.set("persona", persona(persona));
        body.set("job", job(job));
        if (answer != null) {
            ObjectNode a = body.putObject("answer");
            a.put("question", answer.question());
            a.put("category", answer.category());
            a.put("text", answer.text());
        }
        body.put("need_next", needNext);
        ArrayNode askedNode = body.putArray("asked");
        asked.forEach(askedNode::add);
        ArrayNode prepNode = body.putArray("prep_questions");
        for (PrepQuestion q : prep) {
            prepNode.addObject().put("category", q.category()).put("question", q.question());
        }
        JsonNode response = post("/v1/mock-interview/turn", body, TURN_FEATURE, userId);
        BigDecimal spent = BigDecimal.ZERO;
        try {
            Optional<Turn> parsed = parseTurn(response, answer, needNext, asked, prep, BigDecimal.ZERO);
            spent = record(userId, response.get("usage"), TURN_FEATURE,
                    parsed.isPresent() ? AiCallStatus.SUCCEEDED : AiCallStatus.FAILED);
            if (parsed.isEmpty()) {
                throw new MockUnavailableException("unexpected ai-service response", spent);
            }
            return new Turn(parsed.get().feedback(), parsed.get().next(), spent);
        } catch (MockUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MockUnavailableException("unexpected ai-service response", spent);
        }
    }

    private Optional<Turn> parseTurn(JsonNode response, AnswerIn answer, boolean needNext, List<String> asked,
            List<PrepQuestion> prep, BigDecimal credits) {
        if (!mock.promptVersion().equals(response.path("prompt_version").asString(null))
                || !response.path("dropped_claims").isInt() || !response.path("answer_redactions").isInt()
                || !response.path("scores_capped").isBoolean()) {
            return Optional.empty();
        }
        FeedbackView feedback = null;
        if (answer != null) {
            Optional<FeedbackView> fb = feedback(response.get("feedback"), answer.category(), answer.text());
            if (fb.isEmpty()) {
                return Optional.empty();
            }
            feedback = fb.get();
        } else if (response.hasNonNull("feedback")) {
            return Optional.empty();
        }
        NextQuestion next = null;
        JsonNode nq = response.get("next_question");
        if (needNext) {
            Optional<NextQuestion> parsed = nextQuestion(nq, asked, prep);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            next = parsed.get();
        } else if (nq != null && !nq.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new Turn(feedback, next, credits));
    }

    // --- summary ---

    /** The summary of a session from its stored turns; the averages are computed here and ai-service's must agree. */
    Summary summarize(UUID userId, PersonaView persona, JobForBrief job, List<SummaryTurnIn> turns,
            boolean endedEarly) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", mock.promptVersion());
        body.set("persona", persona(persona));
        ObjectNode jobNode = body.putObject("job");
        jobNode.put("title", clip(job.title(), 400));
        if (job.company() != null && !job.company().isBlank()) {
            jobNode.put("company", clip(job.company(), 400));
        }
        ArrayNode turnsNode = body.putArray("turns");
        for (SummaryTurnIn t : turns) {
            ObjectNode node = turnsNode.addObject();
            node.put("question", t.question());
            node.put("category", t.category());
            node.set("feedback", feedbackNode(t.feedback()));
        }
        body.put("ended_early", endedEarly);
        JsonNode response = post("/v1/mock-interview/summary", body, SUMMARY_FEATURE, userId);
        BigDecimal spent = BigDecimal.ZERO;
        try {
            Optional<SummaryView> parsed = parseSummary(response, turns);
            spent = record(userId, response.get("usage"), SUMMARY_FEATURE,
                    parsed.isPresent() ? AiCallStatus.SUCCEEDED : AiCallStatus.FAILED);
            if (parsed.isEmpty()) {
                throw new MockUnavailableException("unexpected ai-service response", spent);
            }
            return new Summary(parsed.get(), spent);
        } catch (MockUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MockUnavailableException("unexpected ai-service response", spent);
        }
    }

    private Optional<SummaryView> parseSummary(JsonNode response, List<SummaryTurnIn> turns) {
        if (!mock.promptVersion().equals(response.path("prompt_version").asString(null))
                || !response.path("model").isString() || response.path("turns_answered").asInt(-1) != turns.size()
                || !response.path("fallback_used").isBoolean()) {
            return Optional.empty();
        }
        AveragesView mine = averages(turns);
        JsonNode theirs = response.path("averages");
        if (!close(theirs.get("structure"), mine.structure()) || !close(theirs.get("relevance"), mine.relevance())
                || !close(theirs.get("specificity"), mine.specificity())
                || !close(theirs.get("overall"), mine.overall())
                || (mine.starCompleteness() == null) != (theirs.path("star_completeness").isNull()
                        || !theirs.has("star_completeness"))
                || (mine.starCompleteness() != null
                        && !close(theirs.get("star_completeness"), mine.starCompleteness()))) {
            log.warn("ai-service averages disagree with the stored feedback");
            return Optional.empty();
        }
        Set<String> stored = new HashSet<>();
        for (SummaryTurnIn t : turns) {
            t.feedback().strengths().forEach(p -> stored.add(p.text()));
            t.feedback().improvements().forEach(p -> stored.add(p.text()));
        }
        Optional<List<String>> strengths = texts(response.get("top_strengths"), 3, 0, stored);
        Optional<List<String>> improvements = texts(response.get("top_improvements"), 3, 0, stored);
        Optional<List<String>> steps = texts(response.get("next_steps"), 3, 3, null);
        JsonNode narrative = response.get("narrative");
        if (strengths.isEmpty() || improvements.isEmpty() || steps.isEmpty() || !text(narrative, 900)) {
            return Optional.empty();
        }
        return Optional.of(new SummaryView(turns.size(), mine, strengths.get(), improvements.get(),
                narrative.asString(), steps.get(), response.get("model").asString(), null));
    }

    /** Means over the turns, to two decimals, half up; star only over the turns that have a star score. */
    static AveragesView averages(List<SummaryTurnIn> turns) {
        List<FeedbackView> fb = turns.stream().map(SummaryTurnIn::feedback).toList();
        double star = fb.stream().filter(f -> f.star().score() != null).mapToInt(f -> f.star().score()).average()
                .orElse(Double.NaN);
        return new AveragesView(mean(fb.stream().mapToInt(FeedbackView::structure).average().orElse(0)),
                mean(fb.stream().mapToInt(FeedbackView::relevance).average().orElse(0)),
                mean(fb.stream().mapToInt(FeedbackView::specificity).average().orElse(0)),
                Double.isNaN(star) ? null : mean(star),
                mean(fb.stream().mapToInt(FeedbackView::overall).average().orElse(0)));
    }

    private static double mean(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean close(JsonNode value, double expected) {
        return value != null && value.isNumber() && Math.abs(value.asDouble() - expected) <= 0.011;
    }

    private static Optional<List<String>> texts(JsonNode node, int max, int exactly, Set<String> mustBeIn) {
        if (node == null || !node.isArray() || node.size() > max || (exactly > 0 && node.size() != exactly)) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (!text(n, 300) || (mustBeIn != null && !mustBeIn.contains(n.asString()))) {
                return Optional.empty();
            }
            out.add(n.asString());
        }
        return Optional.of(out);
    }

    // --- feedback checks ---

    /**
     * The feedback if it is exactly what the contract promises: every score a whole number from 1 to 5, STAR complete
     * for a behavioural question and entirely null otherwise, strengths with quotes that are in the answer, and no
     * placeholder in any text.
     */
    static Optional<FeedbackView> feedback(JsonNode fb, String category, String answer) {
        if (fb == null || !fb.isObject()) {
            return Optional.empty();
        }
        Integer structure = score(fb.get("structure"));
        Integer relevance = score(fb.get("relevance"));
        Integer specificity = score(fb.get("specificity"));
        Integer overall = score(fb.get("overall"));
        Optional<StarView> star = star(fb.get("star"), "behavioral".equals(category));
        Optional<List<PointView>> strengths = points(fb.get("strengths"), 0, 5, true, answer);
        Optional<List<PointView>> improvements = points(fb.get("improvements"), 1, 5, false, answer);
        JsonNode evidence = fb.get("evidence");
        if (structure == null || relevance == null || specificity == null || overall == null || star.isEmpty()
                || strengths.isEmpty() || improvements.isEmpty() || evidence == null || !evidence.isArray()
                || evidence.size() > 10) {
            return Optional.empty();
        }
        List<String> quotes = new ArrayList<>();
        for (JsonNode q : evidence) {
            if (!q.isString() || !contains(answer, q.asString())) {
                return Optional.empty();
            }
            quotes.add(q.asString());
        }
        return Optional.of(new FeedbackView(structure, relevance, specificity, star.get(), overall, strengths.get(),
                improvements.get(), quotes));
    }

    private static Integer score(JsonNode node) {
        if (node == null || !node.isInt() || node.intValue() < 1 || node.intValue() > 5) {
            return null;
        }
        return node.intValue();
    }

    private static Optional<StarView> star(JsonNode node, boolean behavioral) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        String[] flags = { "situation", "task", "action", "result" };
        if (!behavioral) {
            boolean allNull = node.path("score").isNull() && node.has("score");
            for (String f : flags) {
                allNull &= node.has(f) && node.get(f).isNull();
            }
            return allNull ? Optional.of(new StarView(null, null, null, null, null)) : Optional.empty();
        }
        Integer score = score(node.get("score"));
        boolean[] values = new boolean[4];
        int present = 0;
        for (int i = 0; i < flags.length; i++) {
            JsonNode f = node.get(flags[i]);
            if (f == null || !f.isBoolean()) {
                return Optional.empty();
            }
            values[i] = f.booleanValue();
            present += values[i] ? 1 : 0;
        }
        if (score == null || score > 1 + present) {
            return Optional.empty();
        }
        return Optional.of(new StarView(score, values[0], values[1], values[2], values[3]));
    }

    private static Optional<List<PointView>> points(JsonNode node, int min, int max, boolean quoteRequired,
            String answer) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            return Optional.empty();
        }
        List<PointView> out = new ArrayList<>();
        for (JsonNode p : node) {
            JsonNode quote = p.get("quote");
            boolean hasQuote = quote != null && !quote.isNull();
            if (!text(p.get("text"), 300) || (quoteRequired && !hasQuote)
                    || (hasQuote && (!quote.isString() || quote.asString().length() < 3
                            || quote.asString().length() > 300 || !contains(answer, quote.asString())))) {
                return Optional.empty();
            }
            out.add(new PointView(p.get("text").asString(), hasQuote ? quote.asString() : null));
        }
        return Optional.of(out);
    }

    private Optional<NextQuestion> nextQuestion(JsonNode node, List<String> asked, List<PrepQuestion> prep) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        String category = node.path("category").asString("");
        String question = node.path("question").asString("");
        String source = node.path("source").asString("");
        if (!CATEGORIES.contains(category) || question.length() < 10 || !text(node.get("question"), 300)
                || !Set.of("prep", "generated").contains(source)) {
            return Optional.empty();
        }
        String folded = fold(question);
        if (asked.stream().anyMatch(a -> fold(a).equals(folded))) {
            return Optional.empty();
        }
        if ("prep".equals(source) && prep.stream().noneMatch(p -> fold(p.question()).equals(folded)
                && p.category().equals(category))) {
            return Optional.empty();
        }
        return Optional.of(new NextQuestion(category, question, source.toUpperCase(Locale.ROOT)));
    }

    private static boolean text(JsonNode value, int max) {
        return value != null && value.isString() && !value.asString().isBlank() && value.asString().length() <= max
                && !PLACEHOLDER.matcher(value.asString()).find();
    }

    // --- normalisation: the quote check ---

    /** Case-folded, invisible characters removed, whitespace collapsed: what a quote is compared as. */
    static String norm(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\p{Cf}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Is {@code quote} a verbatim run of {@code answer}? Case, spacing and invisible characters are ignored. */
    static boolean contains(String answer, String quote) {
        String needle = norm(quote);
        return needle.length() >= 3 && norm(answer).contains(needle);
    }

    /** Question text folded for the repeat check: letters and digits only. */
    static String fold(String text) {
        return norm(text).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    // --- request pieces ---

    private ObjectNode persona(PersonaView p) {
        ObjectNode node = json.createObjectNode();
        node.put("function", p.function());
        node.put("seniority", p.seniority());
        node.put("tone", p.tone());
        node.put("question_style", p.questionStyle());
        return node;
    }

    private ObjectNode job(JobForBrief job) {
        ObjectNode node = json.createObjectNode();
        node.put("title", clip(job.title(), 400));
        if (job.company() != null && !job.company().isBlank()) {
            node.put("company", clip(job.company(), 400));
        }
        if (job.seniority() != null && !job.seniority().isBlank()) {
            node.put("seniority", clip(job.seniority(), 400));
        }
        if (job.descriptionText() != null && !job.descriptionText().isBlank()) {
            node.put("description", clip(job.descriptionText().strip(), interview.descriptionChars()));
        }
        ArrayNode skills = node.putArray("skills");
        job.skills().stream().filter(s -> s != null && !s.isBlank()).map(s -> clip(s.strip(), 100)).limit(60)
                .forEach(skills::add);
        return node;
    }

    /** The feedback as ai-service's strict schema wants it: STAR keys present even when null. */
    private ObjectNode feedbackNode(FeedbackView f) {
        ObjectNode node = json.createObjectNode();
        node.put("structure", f.structure());
        node.put("relevance", f.relevance());
        node.put("specificity", f.specificity());
        ObjectNode star = node.putObject("star");
        putNullable(star, "score", f.star().score());
        putNullable(star, "situation", f.star().situation());
        putNullable(star, "task", f.star().task());
        putNullable(star, "action", f.star().action());
        putNullable(star, "result", f.star().result());
        node.put("overall", f.overall());
        ArrayNode strengths = node.putArray("strengths");
        f.strengths().forEach(p -> point(strengths.addObject(), p));
        ArrayNode improvements = node.putArray("improvements");
        f.improvements().forEach(p -> point(improvements.addObject(), p));
        ArrayNode evidence = node.putArray("evidence");
        f.evidence().forEach(evidence::add);
        return node;
    }

    private static void point(ObjectNode node, PointView p) {
        node.put("text", p.text());
        if (p.quote() == null) {
            node.putNull("quote");
        } else {
            node.put("quote", p.quote());
        }
    }

    private static void putNullable(ObjectNode node, String name, Object value) {
        if (value == null) {
            node.putNull(name);
        } else if (value instanceof Integer i) {
            node.put(name, i);
        } else {
            node.put(name, (Boolean) value);
        }
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // --- http and usage ---

    /**
     * Posts the request and returns the 200 body. On anything else the usage an error body reports is recorded as
     * FAILED first, and {@link MockUnavailableException} carries the credits those calls cost.
     */
    private JsonNode post(String uri, ObjectNode body, String feature, UUID userId) {
        try {
            return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body)).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        return interpret(userId, feature, status, bytes);
                    });
        } catch (RestClientException e) {
            throw new MockUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(),
                    BigDecimal.ZERO);
        }
    }

    private JsonNode interpret(UUID userId, String feature, int status, byte[] bytes) throws IOException {
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new MockUnavailableException("ai-service response too large", BigDecimal.ZERO);
        }
        JsonNode response = readTree(bytes);
        if (status != 200 || response == null || !response.isObject()) {
            BigDecimal spent = BigDecimal.ZERO;
            if (response != null) {
                spent = record(userId, response.get("usage"), feature, AiCallStatus.FAILED);
            }
            String code = response != null && response.path("code").isString() ? response.path("code").asString()
                    : null;
            log.warn("ai-service refused a mock interview request (status={}, code={})", status, code);
            throw new MockUnavailableException("ai-service answered " + status + (code == null ? "" : " " + code),
                    spent);
        }
        return response;
    }

    private JsonNode readTree(byte[] body) {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Records each reported call and returns the credits of those the ledger recorded now (not a duplicate). */
    private BigDecimal record(UUID userId, JsonNode usage, String feature, AiCallStatus status) {
        BigDecimal total = BigDecimal.ZERO;
        if (usage == null || !usage.isArray()) {
            return total;
        }
        for (JsonNode call : usage) {
            Optional<AiUsage> parsed = toUsage(userId, call, feature, status);
            if (parsed.isEmpty()) {
                log.warn("Skipping a malformed ai-service usage entry");
                continue;
            }
            AiUsage u = parsed.get();
            try {
                if (ledger.record(u) == RecordOutcome.RECORDED) {
                    total = total.add(credits.creditsFor(u.costUsd()));
                }
            } catch (RuntimeException e) {
                log.error("UNRECORDED ai usage call={} user={} feature={} model={} inputTokens={} outputTokens={} "
                        + "costUsd={}", u.requestKey(), u.userId(), u.feature(), u.model(), u.inputTokens(),
                        u.outputTokens(), u.costUsd(), e);
            }
        }
        return total;
    }

    private static Optional<AiUsage> toUsage(UUID userId, JsonNode u, String feature, AiCallStatus status) {
        try {
            UUID callId = u.path("call_id").isString() ? UUID.fromString(u.get("call_id").asString())
                    : UUID.randomUUID();
            if (!u.path("provider").isString() || !u.path("model").isString()) {
                return Optional.empty();
            }
            // The ledger's feature is ours, whatever label the service puts on its calls.
            return Optional.of(new AiUsage("ai-service:" + callId, userId, feature, u.get("provider").asString(),
                    u.get("model").asString(), number(u.get("input_tokens")).longValue(),
                    number(u.get("output_tokens")).longValue(), number(u.get("cost_usd")),
                    number(u.get("latency_ms")).longValue(),
                    u.path("prompt_version").isString() ? u.get("prompt_version").asString() : null,
                    u.path("pricing_version").isString() && !u.get("pricing_version").asString().isBlank()
                            ? u.get("pricing_version").asString() : null,
                    status));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static BigDecimal number(JsonNode value) {
        if (value != null && (value.isNumber() || value.isString())) {
            return new BigDecimal(value.asString());
        }
        throw new IllegalArgumentException("not a number");
    }
}
