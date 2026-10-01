package com.jobfinder.core.documents.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jobfinder.core.documents.ApprovedDocument;
import com.jobfinder.core.documents.ApprovedDocuments;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentStatus;

import tools.jackson.databind.json.JsonMapper;

/** Implements the module's public read API: approved documents only, found through their owner. */
@Component
class ApprovedDocumentReader implements ApprovedDocuments {

    private final DocumentStore store;
    private final JsonMapper json;

    ApprovedDocumentReader(DocumentStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    @Override
    public Optional<ApprovedDocument> approved(UUID userId, UUID documentId) {
        return store.find(userId, documentId, false).filter(r -> r.status() == DocumentStatus.APPROVED)
                .map(r -> new ApprovedDocument(r.id(), r.type().name(), r.jobId(), r.jobTitle(), r.jobCompany(),
                        json.writeValueAsString(r.content()), r.approvedAt()));
    }
}
