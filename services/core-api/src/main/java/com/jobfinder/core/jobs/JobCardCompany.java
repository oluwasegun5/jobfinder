package com.jobfinder.core.jobs;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The company of a {@link JobCard}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobCardCompany(UUID id, String name) {
}
