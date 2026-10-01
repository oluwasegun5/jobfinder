package com.jobfinder.core.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.jobfinder.core.identity.AuthTestSupport;

import tools.jackson.databind.json.JsonMapper;

/** The internal endpoints take the service token only, validate what they are sent, and stay out of the public API. */
class EmbeddingInternalApiTests extends AuthTestSupport {

    private static final String BODY = "{\"kind\":\"JOB\",\"ids\":[\"%s\"]}";

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

    @Test
    void noTokenAndAWrongTokenAreRejected() throws Exception {
        mvc.perform(post("/internal/v1/embeddings/inputs").contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted(UUID.randomUUID()))).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/embeddings/inputs").header("X-Service-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/embeddings/backfill")).andExpect(status().isUnauthorized());
    }

    @Test
    void aUsersAccessTokenDoesNotOpenThem() throws Exception {
        Session session = login(registerVerifiedUser(), PASSWORD, newIp());
        mvc.perform(post("/internal/v1/embeddings/inputs").header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theServiceTokenDoesNotOpenTheUserApi() throws Exception {
        mvc.perform(get("/profile").header("X-Service-Token", com.jobfinder.core.AiServiceStubs.TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vectorsFromAnotherModelOrOfTheWrongSizeAreRefused() throws Exception {
        UUID id = UUID.randomUUID();
        String hash = "a".repeat(64);
        worker.results("JOB", "some-other-model", 1024, List.of(StubEmbeddingWorker.item(id, hash)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("embedding_space_mismatch"));
        worker.results("JOB", StubEmbeddingWorker.MODEL, 512, List.of(StubEmbeddingWorker.item(id, hash)))
                .andExpect(status().isConflict());

        List<Float> tooShort = new ArrayList<>(List.of(0.1f, 0.2f));
        worker.results("JOB", StubEmbeddingWorker.MODEL, 1024,
                List.of(Map.of("id", id.toString(), "inputHash", hash, "embedding", tooShort)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_embedding"));
    }

    @Test
    void aVectorForAMissingJobIsCountedNotFailed() throws Exception {
        worker.results("JOB", StubEmbeddingWorker.MODEL, 1024,
                List.of(StubEmbeddingWorker.item(UUID.randomUUID(), "b".repeat(64))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.missing").value(1));
    }

    @Test
    void anEmptyOrOversizedBatchIsABadRequest() throws Exception {
        mvc.perform(post("/internal/v1/embeddings/inputs").header("X-Service-Token",
                com.jobfinder.core.AiServiceStubs.TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"JOB\",\"ids\":[]}")).andExpect(status().isBadRequest());
    }

    @Test
    void theInternalEndpointsAreNotInThePublicOpenApiDocument() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("/internal/").doesNotContain("embeddings");
    }
}
