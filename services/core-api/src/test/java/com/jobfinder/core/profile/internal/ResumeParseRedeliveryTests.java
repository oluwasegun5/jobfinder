package com.jobfinder.core.profile.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.AiServiceStubs;
import com.jobfinder.core.profile.ResumeParsingTestSupport;

import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ delivers at least once, so a parse message can arrive again (consumer crash before the
 * ack, broker restart) or twice at once. None of that may create a duplicate {@code resume_version}
 * (UNIQUE (resume_id, version_number)), clobber a version the user has since edited, resurrect a
 * failed parse, or surface as an unhandled exception.
 *
 * <p>Most tests drive the worker directly on rows seeded without a queued message, so the running
 * consumer cannot race them; the last ones go through the real queue.
 */
class ResumeParseRedeliveryTests extends ResumeParsingTestSupport {

    @Autowired
    private ResumeParseWorker worker;

    @Autowired
    private ResumeParseDispatcher dispatcher;

    @Autowired
    private ResumeParseStore store;

    @Autowired
    private ResumeParsingProperties properties;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JsonMapper json;

    private record Seed(UUID userId, UUID resumeId) {
        ResumeParseMessage message() {
            return new ResumeParseMessage(resumeId, userId, 1);
        }
    }

    private Seed seedPending() throws Exception {
        return seed(userIdOf(newSession().accessToken()), "PENDING", null);
    }

    /** A resume with an empty upload version and its stored file, but nothing queued. */
    private Seed seed(UUID userId, String status, String error) {
        UUID resumeId = UUID.randomUUID();
        String key = "resumes/" + userId + "/" + resumeId + ".pdf";
        putObject(key);
        jdbc.update("insert into resumes (id, user_id, label, file_key, file_type, size_bytes, is_primary, "
                + "parse_status, parse_error, created_at, updated_at) "
                + "values (?, ?, 'seed', ?, 'PDF', ?, false, ?, ?, now(), now())", resumeId, userId, key,
                pdf().length, status, error);
        jdbc.update("insert into resume_versions (id, resume_id, version_number, source, created_at, updated_at) "
                + "values (?, ?, 1, 'UPLOAD', now(), now())", UUID.randomUUID(), resumeId);
        return new Seed(userId, resumeId);
    }

    /** What the user's own edit (a later feature) leaves behind: a further version with its own content. */
    private void addEditedVersion(UUID resumeId) {
        jdbc.update("insert into resume_versions (id, resume_id, version_number, structured, source, created_at, "
                + "updated_at) values (?, ?, 2, cast('{\"edited\": true}' as jsonb), 'EDIT', now(), now())",
                UUID.randomUUID(), resumeId);
    }

    private Timestamp updatedAt(UUID resumeId) {
        return jdbc.queryForObject("select updated_at from resumes where id = ?", Timestamp.class, resumeId);
    }

    @Test
    void redeliveringAParsedMessageChangesNothingAndMakesNoSecondLlmCall() throws Exception {
        Seed seed = seedPending();
        stubParseOk(seed.userId());

        worker.handle(seed.message());
        assertThat(parseStatus(seed.resumeId())).isEqualTo("PARSED");
        String parsed = structured(seed.resumeId(), 1);
        Timestamp updated = updatedAt(seed.resumeId());

        assertThatCode(() -> {
            worker.handle(seed.message());
            worker.handle(seed.message());
        }).doesNotThrowAnyException();

        assertThat(versionCount(seed.resumeId())).isEqualTo(1);
        assertThat(structured(seed.resumeId(), 1)).isEqualTo(parsed);
        assertThat(updatedAt(seed.resumeId())).isEqualTo(updated);
        assertThat(parseRequests(seed.userId())).isEqualTo(1);
    }

    @Test
    void aRedeliveryAfterTheUserEditedTheCvLeavesTheEditAlone() throws Exception {
        Seed seed = seedPending();
        stubParseOk(seed.userId());
        worker.handle(seed.message());
        String parsed = structured(seed.resumeId(), 1);
        addEditedVersion(seed.resumeId());

        worker.handle(seed.message());
        worker.handle(new ResumeParseMessage(seed.resumeId(), seed.userId(), 2));

        assertThat(versionCount(seed.resumeId())).isEqualTo(2);
        assertThat(structured(seed.resumeId(), 1)).isEqualTo(parsed);
        assertThat(structured(seed.resumeId(), 2)).isEqualTo("{\"edited\": true}");
        assertThat(count("select count(*) from resume_versions where resume_id = ? and source = 'EDIT'",
                seed.resumeId())).isEqualTo(1);
        assertThat(parseRequests(seed.userId())).isEqualTo(1);
    }

    @Test
    void aParseThatFinishesAfterTheUserAlreadyEditedFillsOnlyTheUploadVersion() throws Exception {
        Seed seed = seedPending();
        addEditedVersion(seed.resumeId());
        stubParseOk(seed.userId());

        worker.handle(seed.message());

        assertThat(parseStatus(seed.resumeId())).isEqualTo("PARSED");
        assertThat(structured(seed.resumeId(), 1)).isNotNull().contains("Jordan Reyes");
        assertThat(structured(seed.resumeId(), 2)).isEqualTo("{\"edited\": true}");
        assertThat(versionCount(seed.resumeId())).isEqualTo(2);
    }

    @Test
    void twoDeliveriesProcessedAtTheSameTimeProduceOneResultAndNoError() throws Exception {
        Seed seed = seedPending();
        // Slow enough that both deliveries pass the "still PENDING?" check before either writes.
        stubParse(seed.userId(), okJson(AiServiceStubs.parseOkBody()).withFixedDelay(400));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Void>> deliveries = List.of(
                    pool.submit(() -> deliverWhenReleased(go, seed)),
                    pool.submit(() -> deliverWhenReleased(go, seed)));
            go.countDown();
            for (Future<Void> delivery : deliveries) {
                assertThatCode(() -> delivery.get()).doesNotThrowAnyException();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(parseStatus(seed.resumeId())).isEqualTo("PARSED");
        assertThat(versionCount(seed.resumeId())).isEqualTo(1);
        assertThat(structured(seed.resumeId(), 1)).contains("Jordan Reyes");
        assertThat(parseRequests(seed.userId())).isBetween(1, 2);
    }

    private Void deliverWhenReleased(CountDownLatch go, Seed seed) throws InterruptedException {
        go.await();
        worker.handle(seed.message());
        return null;
    }

    @Test
    void aFailedParseIsNotResurrectedByARedelivery() throws Exception {
        Seed seed = seed(userIdOf(newSession().accessToken()), "FAILED", "no_extractable_text");
        stubParseOk(seed.userId());

        worker.handle(seed.message());

        assertThat(parseStatus(seed.resumeId())).isEqualTo("FAILED");
        assertThat(parseError(seed.resumeId())).isEqualTo("no_extractable_text");
        assertThat(structured(seed.resumeId(), 1)).isNull();
        assertThat(parseRequests(seed.userId())).isZero();
    }

    @Test
    void aMessageNamingSomeoneElseAsOwnerIsIgnored() throws Exception {
        Seed seed = seedPending();
        stubParseOk(seed.userId());

        worker.handle(new ResumeParseMessage(seed.resumeId(), UUID.randomUUID(), 1));

        assertThat(parseStatus(seed.resumeId())).isEqualTo("PENDING");
        assertThat(parseRequests(seed.userId())).isZero();
    }

    @Test
    void aMessageForADeletedResumeIsIgnored() {
        assertThatCode(() -> worker.handle(new ResumeParseMessage(UUID.randomUUID(), UUID.randomUUID(), 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void aStoredFileThatHasGoneFailsTheParseWithoutCallingTheParser() throws Exception {
        Seed seed = seedPending();
        stubParseOk(seed.userId());
        s3.deleteObject(b -> b.bucket(bucket).key(fileKey(seed.resumeId())));

        worker.handle(seed.message());

        assertThat(parseStatus(seed.resumeId())).isEqualTo("FAILED");
        assertThat(parseError(seed.resumeId())).isEqualTo("file_missing");
        assertThat(parseRequests(seed.userId())).isZero();
    }

    @Test
    void aBrokerThatCannotBeReachedFailsTheResumeAtOnceInsteadOfLeavingItPending() throws Exception {
        Seed seed = seedPending();
        CachingConnectionFactory unreachable = new CachingConnectionFactory("127.0.0.1", 1);
        unreachable.setConnectionTimeout(500);
        try {
            new ResumeParseDispatcher(new RabbitTemplate(unreachable), json, properties, store)
                    .on(new ResumeUploaded(seed.resumeId(), seed.userId(), 1));
        } finally {
            unreachable.destroy();
        }

        assertThat(parseStatus(seed.resumeId())).isEqualTo("FAILED");
        assertThat(parseError(seed.resumeId())).isEqualTo("parse_queue_unavailable");
    }

    @Test
    void duplicateMessagesOnTheRealQueueEndInOneParsedVersionAndNothingDeadLettered() throws Exception {
        Seed seed = seedPending();
        stubParse(seed.userId(), okJson(AiServiceStubs.parseOkBody()).withFixedDelay(150));

        for (int i = 0; i < 4; i++) {
            dispatcher.publish(seed.message());
        }
        awaitStatus(seed.resumeId(), "PARSED");
        // Let the remaining duplicates be consumed too.
        Awaitility.await().atMost(Duration.ofSeconds(15)).during(Duration.ofMillis(700))
                .until(() -> admin.getQueueInfo(properties.queue()).getMessageCount() == 0);

        assertThat(versionCount(seed.resumeId())).isEqualTo(1);
        assertThat(structured(seed.resumeId(), 1)).contains("Jordan Reyes");
        // At most the two concurrent consumers can both get past the "still PENDING?" check.
        assertThat(parseRequests(seed.userId())).isBetween(1, 2);
        assertThat(rabbit.receive(properties.deadLetterQueue())).isNull();
    }

    @Test
    void aMalformedMessageIsParkedInTheDeadLetterQueueAndTheConsumerCarriesOn() throws Exception {
        for (String garbage : List.of("not json at all", "{\"resumeId\": \"nope\"}", "{}")) {
            Message poison = MessageBuilder.withBody(garbage.getBytes(StandardCharsets.UTF_8)).build();
            rabbit.send("", properties.queue(), poison);
            Message parked = rabbit.receive(properties.deadLetterQueue(), 10_000);
            assertThat(parked).as("dead-lettered: %s", garbage).isNotNull();
            assertThat(new String(parked.getBody(), StandardCharsets.UTF_8)).isEqualTo(garbage);
        }

        Seed seed = seedPending();
        stubParseOk(seed.userId());
        dispatcher.publish(seed.message());
        awaitStatus(seed.resumeId(), "PARSED");
    }
}
