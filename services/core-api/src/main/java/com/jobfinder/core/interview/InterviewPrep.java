package com.jobfinder.core.interview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Interview preparation for a job (docs/adr/0033-interview-prep.md): likely questions, by category, and a company
 * brief made only from the job posting and the company record, in which every claim names the field it came from and
 * what the posting does not say is listed as unknown.
 *
 * <p>Everything is scoped to the user it is called for: someone else's prep is never found, so it is "not found" and
 * never "forbidden". A prep is made once per user, job and prompt version.
 */
public interface InterviewPrep {

    /**
     * The prep of the user for the job: the stored one (not created, no model call, nothing charged to the daily cap;
     * with status {@code GENERATING} while another request is still making it) or a new one made by ai-service.
     *
     * @throws com.jobfinder.core.shared.ApiException {@code resume_required} (409), {@code job_not_found} (404),
     *         {@code generation_in_progress} (409, when a request that was making it was superseded),
     *         {@code interview_prep_unavailable} (503); the daily cap ({@code ai_daily_cap_reached}, 429) is raised
     *         by the billing module
     */
    Generated generate(UUID userId, UUID jobId);

    /** @throws com.jobfinder.core.shared.ApiException {@code interview_prep_not_found} (404) if it is not the user's */
    InterviewPrepView get(UUID userId, UUID prepId);

    /** The prep, and whether this call made it. */
    record Generated(InterviewPrepView prep, boolean created) {
    }

    /**
     * A prep. {@code status} is {@code READY}, or {@code GENERATING} when it is read while being made (then
     * {@code questions} is empty and {@code companyBrief} is null). {@code model} wrote the questions.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InterviewPrepView(UUID id, UUID jobId, String jobTitle, String jobCompany, String status,
            String promptVersion, String model, Instant createdAt, List<InterviewQuestionView> questions,
            CompanyBriefView companyBrief) {
    }

    /** {@code category} is {@code behavioral}, {@code technical} or {@code role_specific}; {@code difficulty} is
     *  {@code easy}, {@code medium} or {@code hard}. */
    record InterviewQuestionView(String category, String question, String rationale, String difficulty) {
    }

    /**
     * What the posting and the company record say, by section, and what they leave open. {@code unknowns} are
     * statements of what is missing, never guesses; {@code droppedClaims} counts statements the grounding check removed
     * because the fields did not support them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CompanyBriefView(String model, List<BriefSectionView> sections, List<String> unknowns,
            int droppedClaims) {
    }

    record BriefSectionView(String id, String title, List<BriefClaimView> claims) {
    }

    /** {@code source} is the field the statement comes from ({@code job.description}, {@code company.size}, ...) and
     *  {@code evidence} the words of that field that support it. */
    record BriefClaimView(String statement, String source, String evidence) {
    }
}
