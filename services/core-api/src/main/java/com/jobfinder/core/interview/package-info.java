/**
 * Interview preparation (docs/adr/0033-interview-prep.md): likely questions for a job and a company brief built only
 * from the job posting and the company record, both made by ai-service and stored per user, job and prompt version.
 * Other modules use {@link com.jobfinder.core.interview.InterviewPrep}; everything else lives in {@code internal}.
 * Text mock interviews (docs/adr/0034-mock-interview.md) are used through {@link com.jobfinder.core.interview.MockInterviews}:
 * sessions of turns with per-answer rubric feedback and a summary, metered per call through the billing module.
 */
package com.jobfinder.core.interview;
