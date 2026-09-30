package com.jobfinder.core.profile.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ResumeVersionRepository extends JpaRepository<ResumeVersion, UUID> {
}
