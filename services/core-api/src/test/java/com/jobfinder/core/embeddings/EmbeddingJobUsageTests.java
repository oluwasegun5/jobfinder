package com.jobfinder.core.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.ingestion.JobPipelineTestSupport;

import tools.jackson.databind.json.JsonMapper;

/** Job embeddings are system work: recorded without an owner, debited to nobody and never blocked by a user's cap. */
@AutoConfigureMockMvc
class EmbeddingJobUsageTests extends JobPipelineTestSupport {

    private static final String QUEUE = "jobs.embed";

    @Autowired
    MockMvc mvc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    JsonMapper json;

    private StubEmbeddingWorker worker;

    @BeforeEach
    void setUp() {
        worker = new StubEmbeddingWorker(mvc, rabbit, admin, json);
        worker.purge(QUEUE);
    }

    @Test
    void theCostOfAJobEmbeddingIsRecordedAsSystemUsageAndRepeatsChargeNothing() throws Exception {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("a", "Engineer", "Acme", "Lagos", "description", "Builds APIs."));
        ingestion.runNow(FAKE).orElseThrow();
        UUID job = jobIdOf(FAKE, "a");
        UUID callId = UUID.randomUUID();
        String inputs = worker.inputs("JOB", job).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        Map<String, Object> item = StubEmbeddingWorker.item(job, JsonPath.read(inputs, "$.items[0].inputHash"));
        List<Map<String, Object>> usage = List.of(StubEmbeddingWorker.usageEntry(callId, null, "embed_job",
                StubEmbeddingWorker.MODEL, "0.0004"));

        for (int attempt = 0; attempt < 2; attempt++) {
            worker.results("JOB", StubEmbeddingWorker.MODEL, StubEmbeddingWorker.DIMENSION, List.of(item), usage)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
        }

        Map<String, Object> call = jdbc.queryForMap("select * from ai_calls where request_key = ?",
                "ai-service:" + callId);
        assertThat(call).containsEntry("feature", "embed_job").containsEntry("cost_micro_usd", 400L)
                .containsEntry("status", "SUCCEEDED").containsEntry("user_id", null);
        assertThat(jdbc.queryForObject("select count(*) from ai_calls where request_key = ?", Integer.class, "ai-service:" + callId)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where ai_call_id = ?", Integer.class, call.get("id"))).isZero();
    }

    @Test
    void jobInputsAreNeverBlockedByTheCap() throws Exception {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("b", "Engineer", "Acme", "Lagos", "description", "Builds more APIs."));
        ingestion.runNow(FAKE).orElseThrow();
        UUID job = jobIdOf(FAKE, "b");

        worker.inputs("JOB", job).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id")
                .value(job.toString()));
    }
}
