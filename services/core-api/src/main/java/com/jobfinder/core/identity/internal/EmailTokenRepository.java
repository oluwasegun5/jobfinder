package com.jobfinder.core.identity.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EmailTokenRepository extends JpaRepository<EmailToken, UUID> {

    Optional<EmailToken> findByTokenHashAndType(String tokenHash, EmailTokenType type);

    /** Atomically consumes a live token; returns 0 if it is unknown, expired or already used. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailToken t set t.usedAt = :now, t.updatedAt = :now where t.tokenHash = :hash and t.type = :type and t.usedAt is null and t.expiresAt > :now")
    int consume(@Param("hash") String hash, @Param("type") EmailTokenType type, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailToken t set t.usedAt = :now, t.updatedAt = :now where t.userId = :userId and t.type = :type and t.usedAt is null")
    int invalidateOutstanding(@Param("userId") UUID userId, @Param("type") EmailTokenType type, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EmailToken t where t.userId = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}
