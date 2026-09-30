package com.jobfinder.core.profile.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** One version of a resume's structured content. The {@code structured} JSON is added by CV parsing (P1.5). */
@Entity
@Table(name = "resume_versions")
class ResumeVersion {

    enum Source {
        UPLOAD, EDIT, TAILORED
    }

    @Id
    private UUID id;

    private UUID resumeId;

    private int versionNumber;

    @Enumerated(EnumType.STRING)
    private Source source;

    private Instant createdAt;

    private Instant updatedAt;

    protected ResumeVersion() {
    }

    ResumeVersion(UUID resumeId, int versionNumber, Source source) {
        this.id = UUID.randomUUID();
        this.resumeId = resumeId;
        this.versionNumber = versionNumber;
        this.source = source;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }
}
