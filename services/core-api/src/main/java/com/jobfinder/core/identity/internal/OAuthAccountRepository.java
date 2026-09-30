package com.jobfinder.core.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OAuthAccount a where a.userId = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}
