package com.jobfinder.core.identity.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "oauth_accounts")
class OAuthAccount {

    static final String GOOGLE = "GOOGLE";

    @Id
    private UUID id;

    private UUID userId;

    private String provider;

    private String providerUserId;

    private Instant createdAt;

    private Instant updatedAt;

    protected OAuthAccount() {
    }

    OAuthAccount(UUID userId, String provider, String providerUserId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
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

    UUID getUserId() {
        return userId;
    }
}
