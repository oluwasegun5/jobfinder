package com.jobfinder.core.embeddings;

import java.util.Optional;
import java.util.UUID;

/** Read access to the vectors of resume versions, for matching (docs/adr/0022-embeddings-pipeline.md). */
public interface ResumeEmbeddings {

    /**
     * The embedding of a resume version, only if it is current: made with the pinned model from exactly the text the
     * version has now. A version that has no vector yet, one whose content was edited since (its new vector is on
     * its way), one embedded with another model, and one without content all answer empty, so a caller never ranks
     * with a vector that describes older content or lives in another embedding space.
     */
    Optional<ResumeVector> currentVector(UUID resumeVersionId);
}
