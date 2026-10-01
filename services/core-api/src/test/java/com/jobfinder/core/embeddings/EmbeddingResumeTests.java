package com.jobfinder.core.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.profile.ResumeParsingTestSupport;

import tools.jackson.databind.json.JsonMapper;

/** A parsed or edited resume version is queued for embedding; the text never contains the contact block. */
class EmbeddingResumeTests extends ResumeParsingTestSupport {

    private static final String QUEUE = "resumes.embed";

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

    private UUID versionId(UUID resumeId, int number) {
        return jdbc.queryForObject("select id from resume_versions where resume_id = ? and version_number = ?",
                UUID.class, resumeId, number);
    }

    @Test
    void aParsedResumeVersionIsQueuedEmbeddedAndKeepsContactDetailsOut() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        UUID resumeId = uploadPdf(session);
        awaitStatus(resumeId, "PARSED");
        UUID version = versionId(resumeId, 1);

        assertThat(worker.awaitIds(QUEUE, Set.of(version))).contains(version);

        String inputs = worker.inputs("RESUME_VERSION", version).andExpect(status().isOk())
                .andExpect(jsonPath("$.inputType").value("query"))
                .andExpect(jsonPath("$.items[0].userId").value(userId.toString()))
                .andReturn().getResponse().getContentAsString();
        String text = JsonPath.read(inputs, "$.items[0].text");
        assertThat(text).isNotBlank().doesNotContain("Jordan Reyes").doesNotContain("@");

        assertThat(JsonPath.<Integer>read(worker.process("RESUME_VERSION", version), "$.applied")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select vector_dims(embedding) from resume_versions where id = ?",
                Integer.class, version)).isEqualTo(1024);
        assertThat(jdbc.queryForObject("select embedding_model from resume_versions where id = ?", String.class,
                version)).isEqualTo("voyage-4");
    }

    @Test
    void anEditedResumeGetsItsOwnEmbedding() throws Exception {
        Session session = newSession();
        stubParseOk(userIdOf(session.accessToken()));
        UUID resumeId = uploadPdf(session);
        awaitStatus(resumeId, "PARSED");
        worker.awaitIds(QUEUE, Set.of(versionId(resumeId, 1)));

        mvc.perform(put("/resumes/" + resumeId + "/content").header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"headline\":\"Platform engineer\",\"skills\":[\"Go\"]}")).andExpect(status().isOk());
        UUID edit = versionId(resumeId, 2);

        assertThat(worker.awaitIds(QUEUE, Set.of(edit))).contains(edit);
        assertThat(JsonPath.<Integer>read(worker.process("RESUME_VERSION", edit), "$.applied")).isEqualTo(1);

        // Saving the same content again changes nothing the vector depends on: no new message.
        worker.drain(QUEUE);
        mvc.perform(put("/resumes/" + resumeId + "/content").header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"headline\":\"Platform engineer\",\"skills\":[\"Go\"]}")).andExpect(status().isOk());
        assertThat(worker.drain(QUEUE)).isEmpty();
    }

    @Test
    void aVersionWithoutContentHasNothingToEmbed() throws Exception {
        Session session = newSession();
        stubParse(userIdOf(session.accessToken()), problem(422, "no_extractable_text", false));
        UUID resumeId = uploadPdf(session);
        awaitStatus(resumeId, "FAILED");

        worker.inputs("RESUME_VERSION", versionId(resumeId, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("NO_CONTENT"));
    }
}
