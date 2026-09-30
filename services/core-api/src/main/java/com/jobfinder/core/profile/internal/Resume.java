package com.jobfinder.core.profile.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "resumes")
class Resume {

    @Id
    private UUID id;

    private UUID userId;

    private String label;

    private String fileKey;

    @Enumerated(EnumType.STRING)
    private ResumeFormat fileType;

    private long sizeBytes;

    @Column(name = "is_primary")
    private boolean primaryFlag;

    // Written only by ResumeParseStore. Never updatable here: a stale entity saved later (say, by
    // setPrimary) must not revert a result the async parser stored in the meantime.
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private ParseStatus parseStatus = ParseStatus.PENDING;

    @Column(updatable = false)
    private String parseError;

    private Instant createdAt;

    private Instant updatedAt;

    protected Resume() {
    }

    Resume(UUID id, UUID userId, String label, String fileKey, ResumeFormat fileType, long sizeBytes,
            boolean primary) {
        this.id = id;
        this.userId = userId;
        this.label = label;
        this.fileKey = fileKey;
        this.fileType = fileType;
        this.sizeBytes = sizeBytes;
        this.primaryFlag = primary;
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

    UUID getUserId() {
        return userId;
    }

    String getLabel() {
        return label;
    }

    String getFileKey() {
        return fileKey;
    }

    ResumeFormat getFileType() {
        return fileType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    boolean isPrimary() {
        return primaryFlag;
    }

    void setPrimary(boolean primary) {
        this.primaryFlag = primary;
    }

    ParseStatus getParseStatus() {
        return parseStatus;
    }

    /** Reason code when parsing failed, else null. */
    String getParseError() {
        return parseError;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
