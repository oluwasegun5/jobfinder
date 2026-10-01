package com.jobfinder.core.jobs;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A job as a list shows it, the same fields as a search result ({@code GET /jobs}): {@code summary} is the start of
 * the description as plain text, {@code applied} and {@code saved} are the caller's own state. As in search, the
 * credit each source's terms require comes with the job page ({@code GET /jobs/{id}}), which the card links to.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobCard(UUID id, String title, JobCardCompany company, String location, String city, String country,
        String workMode, String employmentType, String seniority, JobCardSalary salary, Instant postedAt,
        String status, String summary, boolean saved, boolean applied) {
}
