package com.jobfinder.core.embeddings;

/** A resume version's embedding and the model that made it (the pinned embedding space). */
public record ResumeVector(String model, float[] values) {
}
