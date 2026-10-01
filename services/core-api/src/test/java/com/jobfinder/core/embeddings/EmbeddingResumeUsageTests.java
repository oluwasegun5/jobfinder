package com.jobfinder.core.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;
import com.jobfinder.core.profile.ResumeParsingTestSupport;

import tools.jackson.databind.json.JsonMapper;

/**
 * Resume embeddings are attributed to the resume's owner: their cost is recorded and debited in the same commit as
 * the vector, a repeated write-back charges once, and an owner who used up the daily cap gets no new embedding.
 */
@TestPropertySource(properties = { "app.billing.daily-cap-credits=10", "app.billing.micro-usd-per-credit=1000" })
class EmbeddingResumeUsageTests extends ResumeParsingTestSupport {

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    JsonMapper json;

    @Autowired
    AiUsageLedger ledger;

    private StubEmbeddingWorker worker;

    @BeforeEach
    void setUp() {
        worker = new StubEmbeddingWorker(mvc, rabbit, admin, json);
    }

    private record Parsed(UUID userId, UUID version) {
    }

    private Parsed parsedResume() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        UUID resumeId = uploadPdf(session);
        awaitStatus(resumeId, "PARSED");
        UUID version = jdbc.queryForObject("select id from resume_versions where resume_id = ? and version_number = 1",
                UUID.class, resumeId);
        return new Parsed(userId, version);
    }

    private Map<String, Object> itemFor(UUID version) throws Exception {
        String inputs = worker.inputs("RESUME_VERSION", version).andReturn().getResponse().getContentAsString();
        return StubEmbeddingWorker.item(version, JsonPath.read(inputs, "$.items[0].inputHash"));
    }

    private BigDecimal balance(UUID userId) {
        return jdbc.queryForObject("select coalesce((select balance_after from credit_ledger where user_id = ? "
                + "order by id desc limit 1), 0)", BigDecimal.class, userId);
    }

    @Test
    void theCostOfAResumeEmbeddingIsRecordedAndDebitedToTheOwner() throws Exception {
        Parsed parsed = parsedResume();
        UUID callId = UUID.randomUUID();

        worker.results("RESUME_VERSION", StubEmbeddingWorker.MODEL, StubEmbeddingWorker.DIMENSION,
                List.of(itemFor(parsed.version())), List.of(StubEmbeddingWorker.usageEntry(callId, parsed.userId(),
                        "embed_resume", StubEmbeddingWorker.MODEL, "0.002")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));

        Map<String, Object> call = jdbc.queryForMap("select * from ai_calls where request_key = ?",
                "ai-service:" + callId);
        assertThat(call).containsEntry("user_id", parsed.userId()).containsEntry("feature", "embed_resume")
                .containsEntry("model", "voyage-4").containsEntry("input_tokens", 12L)
                .containsEntry("output_tokens", 0L).containsEntry("cost_micro_usd", 2_000L)
                .containsEntry("pricing_version", "test").containsEntry("status", "SUCCEEDED");
        assertThat(balance(parsed.userId())).isEqualByComparingTo("-2");
    }

    @Test
    void aRepeatedWriteBackWithTheSameCallIdChargesOnce() throws Exception {
        Parsed parsed = parsedResume();
        UUID callId = UUID.randomUUID();
        Map<String, Object> item = itemFor(parsed.version());
        List<Map<String, Object>> usage = List.of(StubEmbeddingWorker.usageEntry(callId, parsed.userId(),
                "embed_resume", StubEmbeddingWorker.MODEL, "0.002"));

        for (int attempt = 0; attempt < 3; attempt++) {
            worker.results("RESUME_VERSION", StubEmbeddingWorker.MODEL, StubEmbeddingWorker.DIMENSION,
                    List.of(item), usage).andExpect(status().isOk());
        }

        assertThat(count("select count(*) from ai_calls where request_key = ?", "ai-service:" + callId)).isOne();
        assertThat(count("select count(*) from credit_ledger where user_id = ?", parsed.userId())).isOne();
        assertThat(balance(parsed.userId())).isEqualByComparingTo("-2");
    }

    @Test
    void aSenderWithoutACallIdStillGetsIdempotentRecording() throws Exception {
        Parsed parsed = parsedResume();
        Map<String, Object> item = itemFor(parsed.version());
        Map<String, Object> usage = new java.util.LinkedHashMap<>(StubEmbeddingWorker.usageEntry(UUID.randomUUID(),
                parsed.userId(), "embed_resume", StubEmbeddingWorker.MODEL, "0.002"));
        usage.remove("callId");

        for (int attempt = 0; attempt < 2; attempt++) {
            worker.results("RESUME_VERSION", StubEmbeddingWorker.MODEL, StubEmbeddingWorker.DIMENSION,
                    List.of(item), List.of(usage)).andExpect(status().isOk());
        }

        assertThat(count("select count(*) from ai_calls where user_id = ?", parsed.userId())).isOne();
        assertThat(balance(parsed.userId())).isEqualByComparingTo("-2");
    }

    @Test
    void usageIsRecordedEvenWhenTheVectorTurnedOutStale() throws Exception {
        Parsed parsed = parsedResume();
        UUID callId = UUID.randomUUID();

        // The text changed since ai-service fetched it (the hash no longer matches): the vector is dropped, but the
        // provider call was made and billed.
        worker.results("RESUME_VERSION", StubEmbeddingWorker.MODEL, StubEmbeddingWorker.DIMENSION,
                List.of(StubEmbeddingWorker.item(parsed.version(), "c".repeat(64))),
                List.of(StubEmbeddingWorker.usageEntry(callId, parsed.userId(), "embed_resume",
                        StubEmbeddingWorker.MODEL, "0.002")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(1));

        assertThat(count("select count(*) from ai_calls where request_key = ?", "ai-service:" + callId)).isOne();
        assertThat(balance(parsed.userId())).isEqualByComparingTo("-2");
    }

    @Test
    void anOwnerWhoUsedUpTheDailyCapGetsNoNewEmbedding() throws Exception {
        Parsed parsed = parsedResume();
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), parsed.userId(), "parse_resume", "test", "m", 1, 1,
                new BigDecimal("0.02"), 1, null, null, AiCallStatus.SUCCEEDED));

        worker.inputs("RESUME_VERSION", parsed.version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.skipped[0].reason").value("AI_DAILY_CAP_REACHED"));

        // After the reset (the day's lines no longer count) the same version is embedded.
        jdbc.update("delete from credit_ledger where user_id = ?", parsed.userId());
        worker.inputs("RESUME_VERSION", parsed.version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(parsed.version().toString()));
    }

    @Test
    void aCappedOwnerDoesNotBlockSomeoneElsesResumeInTheSameBatch() throws Exception {
        Parsed capped = parsedResume();
        Parsed fine = parsedResume();
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), capped.userId(), "parse_resume", "test", "m", 1, 1,
                new BigDecimal("0.02"), 1, null, null, AiCallStatus.SUCCEEDED));

        String body = worker.inputs("RESUME_VERSION", capped.version(), fine.version()).andReturn().getResponse()
                .getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.items[*].id")).containsExactly(fine.version().toString());
        assertThat(JsonPath.<List<String>>read(body, "$.skipped[*].id")).containsExactly(capped.version().toString());
    }
}
