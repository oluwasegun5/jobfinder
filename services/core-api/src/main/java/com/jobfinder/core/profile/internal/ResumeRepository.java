package com.jobfinder.core.profile.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ResumeRepository extends JpaRepository<Resume, UUID> {

    List<Resume> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Ownership-scoped lookup: another user's resume is simply not found. */
    Optional<Resume> findByIdAndUserId(UUID id, UUID userId);

    Optional<Resume> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserId(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Resume r set r.primaryFlag = false where r.userId = :userId and r.primaryFlag = true")
    int clearPrimary(@Param("userId") UUID userId);
}
