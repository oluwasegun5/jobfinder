package com.jobfinder.core.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.profile.ResumeParsingTestSupport;

import tools.jackson.databind.json.JsonMapper;

/**
 * Resume text is never handed to the embedding provider without consent, and a vector for a resume that was deleted
 * meanwhile is discarded rather than stored (docs/adr/0040).
 */
class EmbeddingConsentAndDeletionTests extends ResumeParsingTestSupport {

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
    }

    private record Parsed(Session session, UUID userId, UUID version) {
    }

    private Parsed parsedResume() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        UUID resumeId = uploadPdf(session);
        awaitStatus(resumeId, "PARSED");
        UUID version = jdbc.queryForObject("select id from resume_versions where resume_id = ? and version_number = 1",
                UUID.class, resumeId);
        return new Parsed(session, userId, version);
    }

    @Test
    void withoutConsentTheResumeTextIsNotHandedOutAndWithItIsAgain() throws Exception {
        Parsed p = parsedResume();
        jdbc.update("update users set ai_consent_version = null, ai_consent_at = null where id = ?", p.userId());

        worker.inputs("RESUME_VERSION", p.version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value("CONSENT_REQUIRED"));

        jdbc.update("update users set ai_consent_version = 'test', ai_consent_at = now() where id = ?", p.userId());
        String body = worker.inputs("RESUME_VERSION", p.version()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].id")).containsExactly(p.version().toString());
    }

    @Test
    void aVectorForADeletedResumeIsDiscardedAndItsIdIsNoLongerOffered() throws Exception {
        Parsed p = parsedResume();
        String inputs = worker.inputs("RESUME_VERSION", p.version()).andReturn().getResponse().getContentAsString();
        String hash = JsonPath.read(inputs, "$.items[0].inputHash");
        mvc.perform(delete("/me").header("Authorization", "Bearer " + p.session().accessToken()))
                .andExpect(status().isNoContent());

        worker.inputs("RESUME_VERSION", p.version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("NOT_FOUND"));
        worker.results("RESUME_VERSION", StubEmbeddingWorker.MODEL, StubEmbeddingWorker.DIMENSION,
                List.of(StubEmbeddingWorker.item(p.version(), hash))).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0)).andExpect(jsonPath("$.missing").value(1));

        assertThat(count("select count(*) from resume_versions where id = ?", p.version())).isZero();
    }
}
