package com.jobfinder.core.embeddings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.AiServiceStubs;

import tools.jackson.databind.json.JsonMapper;

/**
 * Plays ai-service in core-api's tests: reads the embed queues and speaks the internal HTTP contract with a
 * deterministic stand-in for the provider (no test calls a real embedding API). ai-service's own side of the same
 * contract is tested in services/ai-service (tests/test_embedding_worker.py, tests/test_core_api_contract.py).
 */
public class StubEmbeddingWorker {

    public static final String MODEL = "voyage-4";
    public static final int DIMENSION = 1024;
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

    private final MockMvc mvc;
    private final RabbitTemplate rabbit;
    private final AmqpAdmin admin;
    private final JsonMapper json;

    public StubEmbeddingWorker(MockMvc mvc, RabbitTemplate rabbit, AmqpAdmin admin, JsonMapper json) {
        this.mvc = mvc;
        this.rabbit = rabbit;
        this.admin = admin;
        this.json = json;
    }

    public void purge(String... queues) {
        for (String queue : queues) {
            admin.purgeQueue(queue, true);
        }
    }

    /** Takes every message currently on the queue and returns the ids they name (in order). */
    public List<UUID> drain(String queue) {
        List<UUID> ids = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive(queue, 300)) != null) {
            Matcher matcher = ID.matcher(new String(message.getBody(), StandardCharsets.UTF_8));
            if (!matcher.find()) {
                throw new AssertionError("Unexpected message on " + queue);
            }
            ids.add(UUID.fromString(matcher.group(1)));
        }
        return ids;
    }

    /** Drains the queue until it has named all the expected ids, or fails after the timeout. */
    public List<UUID> awaitIds(String queue, Set<UUID> expected) {
        Set<UUID> seen = new HashSet<>();
        List<UUID> all = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
            List<UUID> got = drain(queue);
            all.addAll(got);
            seen.addAll(got);
            return seen.containsAll(expected);
        });
        return all;
    }

    public ResultActions inputs(String kind, UUID... ids) throws Exception {
        return mvc.perform(post("/internal/v1/embeddings/inputs").header("X-Service-Token", AiServiceStubs.TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", kind, "ids", List.of(ids)))));
    }

    public ResultActions results(String kind, String model, int dimension, List<Map<String, Object>> items)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("model", model);
        body.put("dimension", dimension);
        body.put("items", items);
        body.put("usage", List.of(Map.of("feature", "embed_" + kind.toLowerCase(), "provider", "stub", "model", model,
                "inputTokens", 12, "costUsd", "0.000001", "latencyMs", 3)));
        return mvc.perform(put("/internal/v1/embeddings/results").header("X-Service-Token", AiServiceStubs.TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    public ResultActions backfill(String scope) throws Exception {
        return mvc.perform(post("/internal/v1/embeddings/backfill?scope=" + scope)
                .header("X-Service-Token", AiServiceStubs.TOKEN));
    }

    /** One result item: the vector is derived from the input hash, so equal texts get equal vectors. */
    public static Map<String, Object> item(UUID id, String inputHash) {
        double seed = inputHash.chars().limit(8).sum();
        List<Float> vector = new ArrayList<>();
        for (int i = 0; i < DIMENSION; i++) {
            vector.add((float) Math.sin(seed + i));
        }
        return Map.of("id", id.toString(), "inputHash", inputHash, "embedding", vector);
    }

    /** Fetches the inputs for the ids and stores a stub vector for each; returns the {@code results} response body. */
    public String process(String kind, UUID... ids) throws Exception {
        String inputs = inputs(kind, ids).andReturn().getResponse().getContentAsString();
        List<String> idList = JsonPath.read(inputs, "$.items[*].id");
        List<String> hashes = JsonPath.read(inputs, "$.items[*].inputHash");
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < idList.size(); i++) {
            items.add(item(UUID.fromString(idList.get(i)), hashes.get(i)));
        }
        if (items.isEmpty()) {
            return "{\"applied\":0,\"stale\":0,\"missing\":0}";
        }
        return results(kind, MODEL, DIMENSION, items).andReturn().getResponse().getContentAsString();
    }
}
