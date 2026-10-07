package com.jobfinder.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.TestcontainersConfiguration.Mailpit;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Source alerts end to end (ADR 0024): a real run through the pipeline, the alert state in Postgres, and the email
 * as Mailpit received it. The alert recipient is set for every ingestion test (see {@link IngestionTestSupport}), so
 * mails are counted by subject before and after the step under test.
 */
class SourceAlertTests extends IngestionTestSupport {

    private static final String ZERO_SUBJECT = "[JobFinder] Ingestion alert: FAKE returned no jobs";
    private static final String ERRORS_SUBJECT = "[JobFinder] Ingestion alert: FAKE has a high error rate";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private MeterRegistry meters;

    private IngestionRunSummary run() {
        return ingestion.runNow(FAKE).orElseThrow();
    }

    // ---- zero jobs

    @Test
    void aCleanRunThatFetchesNothingAfterOneThatDidSendsOneEmail() throws Exception {
        addTarget(FAKE, "acme");
        fake.postings("acme", 10);
        int before = mails(ZERO_SUBJECT).size();
        double counted = alertsFired("ZERO_JOBS", "email");
        run();
        assertThat(mails(ZERO_SUBJECT)).as("a healthy first run is not an alert").hasSize(before);

        fake.postings("acme", 0);
        IngestionRunSummary empty = run();

        assertThat(empty.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        List<Mail> sent = awaitMails(ZERO_SUBJECT, before + 1);
        Mail mail = sent.get(sent.size() - 1);
        assertThat(mail.to()).contains(ALERT_RECIPIENT);
        assertThat(mail.text()).contains("FAKE (ATS)", "ZERO_JOBS", "fetched 0 postings", "previous clean run fetched 10",
                empty.runId().toString(), "http://localhost:3000/admin/ingestion");
        assertThat(alertsFired("ZERO_JOBS", "email")).isEqualTo(counted + 1);
        assertThat(alertState("ZERO_JOBS")).isEqualTo("ACTIVE");
    }

    @Test
    void consecutiveEmptyRunsDoNotSendAnotherEmailAndRecoveryRearmsTheAlert() throws Exception {
        addTarget(FAKE, "acme");
        fake.postings("acme", 10);
        run();
        int before = mails(ZERO_SUBJECT).size();

        fake.postings("acme", 0);
        run();
        run();
        run();
        assertThat(awaitMails(ZERO_SUBJECT, before + 1)).as("announced once, not on every run").hasSize(before + 1);
        assertThat(jdbc.queryForObject("select occurrences from source_alerts where source_id = ? and rule = 'ZERO_JOBS'",
                Integer.class, sourceId(FAKE))).as("every bad run is still counted").isEqualTo(3);

        fake.postings("acme", 10);
        run();
        assertThat(alertState("ZERO_JOBS")).as("a run that fetches jobs clears the alert").isEqualTo("RESOLVED");
        assertThat(mails(ZERO_SUBJECT)).as("recovery sends nothing").hasSize(before + 1);

        fake.postings("acme", 0);
        run();
        assertThat(awaitMails(ZERO_SUBJECT, before + 2)).as("the next drop is announced again").hasSize(before + 2);
        assertThat(alertState("ZERO_JOBS")).isEqualTo("ACTIVE");
    }

    @Test
    void anAlertThatStaysOpenIsAnnouncedAgainAfterTheReAlertInterval() throws Exception {
        addTarget(FAKE, "acme");
        fake.postings("acme", 10);
        run();
        fake.postings("acme", 0);
        run();
        int before = awaitMails(ZERO_SUBJECT, 1).size();

        run();
        assertThat(mails(ZERO_SUBJECT)).as("within the interval").hasSize(before);

        jdbc.update("update source_alerts set last_notified_at = now() - interval '25 hours' "
                + "where source_id = ? and rule = 'ZERO_JOBS'", sourceId(FAKE));
        run();

        List<Mail> sent = awaitMails(ZERO_SUBJECT, before + 1);
        assertThat(sent.get(sent.size() - 1).text()).contains("This has been going on since", "runs in a row");
        run();
        assertThat(mails(ZERO_SUBJECT)).as("and then quiet again").hasSize(before + 1);
    }

    @Test
    void aSourceThatNeverReturnedAnythingOrOnlyAHandfulIsNotAlerted() throws Exception {
        addTarget(FAKE, "acme");
        int before = mails(ZERO_SUBJECT).size();
        fake.postings("acme", 0);
        run();
        run();
        assertThat(alertState("ZERO_JOBS")).as("empty from the start is just an empty feed").isNull();

        fake.postings("acme", 3);
        run();
        fake.postings("acme", 0);
        run();

        assertThat(alertState("ZERO_JOBS")).as("3 postings is below app.ingestion.alerts.zero-jobs-min-previous")
                .isNull();
        assertThat(mails(ZERO_SUBJECT)).hasSize(before);
    }

    @Test
    void anEmptyRunThatHadErrorsOrNoTargetsIsNotAZeroJobsAlert() throws Exception {
        addTarget(FAKE, "acme");
        fake.postings("acme", 10);
        run();
        int before = mails(ZERO_SUBJECT).size();
        // A target that cannot be fetched at all: fetched is 0 but the failure is the error-rate rule's business.
        addTarget(FAKE, "broken");
        fake.failPermanently("broken");
        fake.postings("acme", 0);

        IngestionRunSummary partial = run();

        assertThat(partial.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(alertState("ZERO_JOBS")).isNull();

        jdbc.update("delete from source_targets where source_id = ?", sourceId(FAKE));
        run();
        assertThat(alertState("ZERO_JOBS")).as("no targets proves nothing").isNull();
        assertThat(mails(ZERO_SUBJECT)).hasSize(before);
    }

    @Test
    void anIncrementalSourceThatHasNothingNewIsNotAlerted() throws Exception {
        addTarget(FAKE, "acme");
        fake.fullListing(false);
        fake.postings("acme", 10);
        run();
        int before = mails(ZERO_SUBJECT).size();

        fake.postings("acme", 0);
        run();

        assertThat(alertState("ZERO_JOBS")).isNull();
        assertThat(mails(ZERO_SUBJECT)).hasSize(before);
    }

    // ---- error rate

    @Test
    void moreThanTwentyPercentOfTargetsFailingSendsOneEmailUntilTheSourceRecovers() throws Exception {
        for (int i = 1; i <= 5; i++) {
            addTarget(FAKE, "board-" + i);
            fake.postings("board-" + i, 2);
        }
        int before = mails(ERRORS_SUBJECT).size();
        double counted = alertsFired("ERROR_RATE", "email");
        run();
        assertThat(mails(ERRORS_SUBJECT)).as("no errors, no alert").hasSize(before);

        fake.failPermanently("board-1");
        run();
        assertThat(mails(ERRORS_SUBJECT)).as("1 of 5 is exactly 20%, not more").hasSize(before);
        assertThat(alertState("ERROR_RATE")).isNull();

        fake.failPermanently("board-2");
        IngestionRunSummary run = run();

        List<Mail> sent = awaitMails(ERRORS_SUBJECT, before + 1);
        assertThat(sent.get(sent.size() - 1).text()).contains("FAKE (ATS)", "ERROR_RATE", "2 of 5 target(s) failed (40%",
                "the limit is 20%", run.runId().toString(), "/admin/ingestion");
        assertThat(alertsFired("ERROR_RATE", "email")).isEqualTo(counted + 1);

        run();
        run();
        assertThat(mails(ERRORS_SUBJECT)).as("two more bad runs, still one email").hasSize(before + 1);

        fake.postings("board-1", 2);
        fake.postings("board-2", 2);
        run();
        assertThat(alertState("ERROR_RATE")).isEqualTo("RESOLVED");

        fake.failPermanently("board-3");
        fake.failPermanently("board-4");
        run();
        assertThat(awaitMails(ERRORS_SUBJECT, before + 2)).as("recovery re-armed the alert").hasSize(before + 2);
    }

    @Test
    void aSmallRunOnlyCountsWhenEveryTargetFailed() throws Exception {
        for (int i = 1; i <= 4; i++) {
            addTarget(FAKE, "board-" + i);
            fake.postings("board-" + i, 2);
        }
        int before = mails(ERRORS_SUBJECT).size();
        fake.failPermanently("board-1");

        run();

        assertThat(alertState("ERROR_RATE")).as("1 of 4 (25%) is too small a sample to blame the source").isNull();

        jdbc.update("delete from source_targets where source_id = ? and identifier <> 'board-1'", sourceId(FAKE));
        IngestionRunSummary failed = run();

        assertThat(failed.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(awaitMails(ERRORS_SUBJECT, before + 1)).hasSize(before + 1);
        assertThat(alertState("ERROR_RATE")).isEqualTo("ACTIVE");
    }

    @Test
    void theEmailNeverCarriesErrorMessagesOrTargetIdentifiers() throws Exception {
        String secretTarget = "secret-board-token-s3cr3t";
        addTarget(FAKE, secretTarget);
        fake.failPermanently(secretTarget);
        int before = mails(ERRORS_SUBJECT).size();

        run();

        String text = awaitMails(ERRORS_SUBJECT, before + 1).get(before).text();
        assertThat(text).doesNotContain("s3cr3t", "not found", "HTTP 404", "PermanentFailure", "SourceFetchException");
    }

    // ---- mail plumbing

    private record Mail(String subject, String text, String to) {
    }

    /** Alert mails to the test recipient with exactly this subject, oldest first. */
    private List<Mail> mails(String subject) throws Exception {
        String query = URLEncoder.encode("to:" + ALERT_RECIPIENT, StandardCharsets.UTF_8);
        String search = fetch("/api/v1/search?query=" + query + "&limit=500");
        List<String> ids = JsonPath.read(search, "$.messages[*].ID");
        List<Mail> found = new ArrayList<>();
        for (String id : ids) {
            String message = fetch("/api/v1/message/" + id);
            if (subject.equals(JsonPath.<String>read(message, "$.Subject"))) {
                found.add(new Mail(subject, JsonPath.read(message, "$.Text"),
                        JsonPath.<List<String>>read(message, "$.To[*].Address").toString()));
            }
        }
        // Mailpit lists newest first.
        java.util.Collections.reverse(found);
        return found;
    }

    private List<Mail> awaitMails(String subject, int count) {
        return Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> mails(subject), found -> found.size() >= count);
    }

    private String fetch(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(mailpit.apiBaseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private double alertsFired(String rule, String delivery) {
        var counter = meters.find("ingestion.alerts.fired").tags("source", FAKE, "rule", rule, "delivery", delivery)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** ACTIVE, RESOLVED, or null when this source never had an alert for the rule. */
    private String alertState(String rule) {
        List<Boolean> rows = jdbc.queryForList("select active from source_alerts where source_id = ? and rule = ?",
                Boolean.class, sourceId(FAKE), rule);
        return rows.isEmpty() ? null : rows.get(0) ? "ACTIVE" : "RESOLVED";
    }
}
