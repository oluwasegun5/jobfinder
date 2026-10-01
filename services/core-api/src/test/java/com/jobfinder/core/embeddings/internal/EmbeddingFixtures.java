package com.jobfinder.core.embeddings.internal;

import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Lets other modules' tests store a resume vector the way the pipeline would: under the hash of the text the embeddings
 * module builds for the content, so the vector counts as current.
 */
public final class EmbeddingFixtures {

    private static final EmbeddingTextBuilder TEXTS = new EmbeddingTextBuilder(new EmbeddingProperties("voyage-4",
            1024, 24000, "jobs.embed", "jobs.embed.dlq", "resumes.embed", "resumes.embed.dlq", 500, true));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EmbeddingFixtures() {
    }

    /** The {@code embedding_input_hash} a current vector of this resume content carries. */
    public static String resumeInputHash(String structuredJson) {
        Object parsed = JSON.readValue(structuredJson, Object.class);
        return EmbeddingTextBuilder.hash(TEXTS.resume(parsed instanceof Map<?, ?> map ? map : Map.of()));
    }
}
