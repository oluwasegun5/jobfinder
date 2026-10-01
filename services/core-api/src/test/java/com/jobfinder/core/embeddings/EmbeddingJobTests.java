package com.jobfinder.core.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.jobfinder.core.ingestion.RawPosting;

import tools.jackson.databind.json.JsonMapper;

/**
 * New and changed jobs are queued for embedding after commit, ai-service's side of the exchange (played by
 * {@link StubEmbeddingWorker}) fetches the text and stores the vector, and every step is safe to repeat.
 */
@AutoConfigureMockMvc
class EmbeddingJobTests extends JobPipelineTestSupport {

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

    private RawPosting engineer(String description) {
        return posting("a", "Engineer", "Acme", "Lagos", "description", description);
    }

    private void ingest(RawPosting posting) {
        fake.customPostings("acme", posting);
        ingestion.runNow(FAKE).orElseThrow();
    }

    private Object column(UUID jobId, String column) {
        return jdbc.queryForObject("select " + column + " from jobs where id = ?", Object.class, jobId);
    }

    @Test
    void aNewJobIsQueuedAndItsVectorIsStored() throws Exception {
        addTarget(FAKE, "acme");
        ingest(engineer("Builds APIs."));
        UUID job = jobIdOf(FAKE, "a");

        assertThat(worker.drain(QUEUE)).containsExactly(job);
        assertThat(column(job, "embedding")).isNull();

        worker.inputs("JOB", job).andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("voyage-4"))
                .andExpect(jsonPath("$.dimension").value(1024))
                .andExpect(jsonPath("$.inputType").value("document"))
                .andExpect(jsonPath("$.items[0].text").value("Title: Engineer\nCompany: Acme\n\nBuilds APIs."))
                .andExpect(jsonPath("$.items[0].userId").doesNotExist());

        assertThat(JsonPath.<Integer>read(worker.process("JOB", job), "$.applied")).isEqualTo(1);
        assertThat(column(job, "embedding_model")).isEqualTo("voyage-4");
        assertThat(((String) column(job, "embedding_input_hash"))).hasSize(64);
        assertThat(column(job, "embedded_at")).isNotNull();
        assertThat(jdbc.queryForObject("select vector_dims(embedding) from jobs where id = ?", Integer.class, job))
                .isEqualTo(1024);
    }

    @Test
    void theInputsResponseHasTheShapeAiServicePins() throws Exception {
        addTarget(FAKE, "acme");
        ingest(engineer("Builds APIs."));
        UUID job = jobIdOf(FAKE, "a");

        String actual = worker.inputs("JOB", job, UUID.randomUUID()).andReturn().getResponse().getContentAsString();
        String pinned;
        try (var in = getClass().getResourceAsStream("/ai-service/embeddings-inputs-ok.json")) {
            pinned = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(shape(json.readValue(actual, Object.class))).isEqualTo(shape(json.readValue(pinned, Object.class)));
        assertThat(JsonPath.<String>read(actual, "$.model")).isEqualTo(JsonPath.<String>read(pinned, "$.model"));
        assertThat(JsonPath.<Integer>read(actual, "$.dimension")).isEqualTo(JsonPath.<Integer>read(pinned, "$.dimension"));
        assertThat(JsonPath.<String>read(actual, "$.inputType")).isEqualTo(JsonPath.<String>read(pinned, "$.inputType"));
    }

    /** The field names of a JSON document, recursively, with values reduced to their kind. */
    private static Object shape(Object node) {
        if (node instanceof java.util.Map<?, ?> map) {
            java.util.Map<Object, Object> result = new java.util.TreeMap<>();
            map.forEach((k, v) -> result.put(k, shape(v)));
            return result;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(EmbeddingJobTests::shape).toList();
        }
        return node == null ? "null" : node.getClass().getSimpleName();
    }

    @Test
    void aRefreshThatChangesNothingTheVectorDependsOnIsNotQueuedAgain() throws Exception {
        addTarget(FAKE, "acme");
        ingest(engineer("Builds APIs."));
        UUID job = jobIdOf(FAKE, "a");
        worker.drain(QUEUE);
        worker.process("JOB", job);

        ingest(engineer("Builds APIs."));

        assertThat(worker.drain(QUEUE)).isEmpty();
    }

    @Test
    void aChangedDescriptionIsQueuedAgainAndAStaleVectorIsDropped() throws Exception {
        addTarget(FAKE, "acme");
        ingest(engineer("Builds APIs."));
        UUID job = jobIdOf(FAKE, "a");
        worker.drain(QUEUE);
        worker.process("JOB", job);
        String first = (String) column(job, "embedding_input_hash");

        ingest(engineer("Builds APIs in Java."));
        assertThat(worker.drain(QUEUE)).containsExactly(job);

        // The worker fetched the text, then the job changed again before the vector came back.
        String inputs = worker.inputs("JOB", job).andReturn().getResponse().getContentAsString();
        String oldHash = JsonPath.read(inputs, "$.items[0].inputHash");
        ingest(engineer("Builds APIs in Kotlin."));
        assertThat(worker.drain(QUEUE)).containsExactly(job);

        worker.results("JOB", StubEmbeddingWorker.MODEL, StubEmbeddingWorker.DIMENSION,
                List.of(StubEmbeddingWorker.item(job, oldHash))).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0)).andExpect(jsonPath("$.stale").value(1));
        assertThat(column(job, "embedding_input_hash")).as("the stale vector was not written").isEqualTo(first);

        assertThat(JsonPath.<Integer>read(worker.process("JOB", job), "$.applied")).isEqualTo(1);
        assertThat(column(job, "embedding_input_hash")).isNotEqualTo(first);
    }

    @Test
    void aRedeliveredMessageForACurrentJobCostsNoProviderCall() throws Exception {
        addTarget(FAKE, "acme");
        ingest(engineer("Builds APIs."));
        UUID job = jobIdOf(FAKE, "a");
        worker.process("JOB", job);

        worker.inputs("JOB", job).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value("UP_TO_DATE"));
        // Storing the same vector again is accepted and changes nothing.
        String inputs = worker.inputs("JOB", job).andReturn().getResponse().getContentAsString();
        assertThat(inputs).contains("UP_TO_DATE");
    }

    @Test
    void anUnknownOrExpiredJobHasNothingToEmbed() throws Exception {
        addTarget(FAKE, "acme");
        ingest(engineer("Builds APIs."));
        UUID job = jobIdOf(FAKE, "a");
        jdbc.update("update jobs set status = 'EXPIRED' where id = ?", job);

        worker.inputs("JOB", job, UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.skipped[?(@.id=='%s')].reason".formatted(job)).value("EXPIRED"))
                .andExpect(jsonPath("$.skipped[1].reason").value("NOT_FOUND"));
    }

    @Test
    void theBackfillQueuesJobsWithoutAVectorOrWithAnotherModelAndSkipsCurrentOnes() throws Exception {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("a", "Engineer", "Acme", "Lagos", "description", "One."),
                posting("b", "Designer", "Acme", "Lagos", "description", "Two."),
                posting("c", "Analyst", "Acme", "Lagos", "description", "Three."));
        ingestion.runNow(FAKE).orElseThrow();
        UUID a = jobIdOf(FAKE, "a");
        UUID b = jobIdOf(FAKE, "b");
        UUID c = jobIdOf(FAKE, "c");
        worker.drain(QUEUE);
        worker.process("JOB", a, c);
        // c was embedded by a model that is no longer the pinned one.
        jdbc.update("update jobs set embedding_model = 'retired-model' where id = ?", c);

        worker.backfill("JOBS").andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("voyage-4"))
                .andExpect(jsonPath("$.jobs.enqueued").isNumber())
                .andExpect(jsonPath("$.resumeVersions.scanned").value(0));

        List<UUID> queued = worker.drain(QUEUE);
        assertThat(queued).contains(b, c).doesNotContain(a);

        // Working off the queue brings everything current, so the next backfill finds none of our jobs.
        worker.process("JOB", b, c);
        worker.backfill("JOBS").andExpect(status().isOk());
        assertThat(worker.drain(QUEUE)).doesNotContain(a, b, c);
    }
}
