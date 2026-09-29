package com.jobfinder.core.identity.internal;

import java.time.Instant;
import java.util.UUID;

import com.jobfinder.core.identity.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
class User {

    @Id
    private UUID id;

    // citext column: uniqueness is case-insensitive in the database. Queries bind a plain string
    // (case-sensitive comparison), so the service always lower-cases before looking up or saving.
    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String email;

    private String passwordHash;

    private Instant emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.ACTIVE;

    private Instant deletedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected User() {
    }

    User(String email, String passwordHash) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
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

    String getEmail() {
        return email;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    void markEmailVerified(Instant at) {
        this.emailVerifiedAt = at;
    }

    Role getRole() {
        return role;
    }

    boolean canAuthenticate() {
        return status == UserStatus.ACTIVE && deletedAt == null;
    }
}
