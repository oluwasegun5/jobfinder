package com.jobfinder.core.embeddings.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jobfinder.core.embeddings.ResumeEmbeddings;
import com.jobfinder.core.embeddings.ResumeVector;

@Component
class ResumeEmbeddingReader implements ResumeEmbeddings {

    private final EmbeddingStore store;
    private final EmbeddingService service;
    private final EmbeddingProperties properties;

    ResumeEmbeddingReader(EmbeddingStore store, EmbeddingService service, EmbeddingProperties properties) {
        this.store = store;
        this.service = service;
        this.properties = properties;
    }

    @Override
    public Optional<ResumeVector> currentVector(UUID resumeVersionId) {
        return store.resumeVersion(resumeVersionId)
                .filter(row -> row.structuredJson() != null && !service.resumeVersionIsStale(row))
                .flatMap(row -> store.resumeVector(resumeVersionId))
                .map(text -> new ResumeVector(properties.model(), parse(text)));
    }

    /** pgvector's text form: {@code [0.1,0.2,...]}. */
    static float[] parse(String text) {
        String body = text.strip();
        body = body.substring(1, body.length() - 1);
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i]);
        }
        return values;
    }
}
