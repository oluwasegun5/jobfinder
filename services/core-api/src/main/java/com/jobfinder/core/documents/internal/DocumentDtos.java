package com.jobfinder.core.documents.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * The request and response shapes of the documents API (docs/adr/0029-resume-tailoring.md), and the two records kept
 * in the JSON columns ({@link Change} and {@link FactCheck}).
 */
final class DocumentDtos {

    private DocumentDtos() {
    }

    enum DocumentType {
        TAILORED_RESUME
    }

    enum DocumentStatus {
        /** A placeholder while ai-service works; never visible for long, and not approvable. */
        GENERATING,
        DRAFT,
        /** A BLOCKING fact-check flag is present: reject or edit the offending change to continue. */
        FACT_CHECK_FAILED,
        /** Final and immutable. */
        APPROVED;

        boolean open() {
            return this != APPROVED;
        }
    }

    enum ChangeState {
        ACCEPTED, REJECTED
    }

    // --- stored in the JSON columns ---

    /**
     * One reviewable unit: the headline, the summary, the whole skills list, or one entry of experience, education,
     * projects or certifications. {@code path} addresses it in the source resume (REPLACE, REMOVE) or in the tailored
     * one (ADD); {@code before} and {@code after} are its JSON.
     */
    record Change(String id, String section, String op, String path, JsonNode before, JsonNode after,
            String rationale, ChangeState state, boolean edited) {

        Change withState(ChangeState newState) {
            return new Change(id, section, op, path, before, after, rationale, newState, edited);
        }

        boolean accepted() {
            return state == ChangeState.ACCEPTED;
        }
    }

    record Flag(String code, String severity, String path, String value, String message) {
    }

    record FactCheck(boolean passed, int blocking, int warnings, List<Flag> flags, String checkerVersion) {
    }

    // --- requests ---

    /** Options of a tailoring run; both are optional. */
    record TailorOptions(Boolean rewriteSummary, @Min(1) @Max(25) Integer maxBulletsPerRole) {
    }

    record TailorRequest(@Valid TailorOptions options) {
    }

    enum OperationType {
        /** Accept or reject one change: a rejected change puts the source's version of that unit back. */
        SET_STATE,
        /** Replace the text of a change's unit (or of a unit that has no change yet, addressed by path). */
        EDIT
    }

    /**
     * One edit to a draft. SET_STATE needs {@code changeId} and {@code state}. EDIT needs {@code changeId} or
     * {@code path} (such as {@code summary} or {@code experience[0]}, a source position) and {@code after}: a string
     * for the headline and summary, an array of strings for the skills, an entry object otherwise. An edit also
     * accepts the change.
     */
    record Operation(@NotNull OperationType op, @Size(max = 20) String changeId, @Size(max = 60) String path,
            ChangeState state, JsonNode after) {
    }

    /** {@code version} is the draft version the client was looking at; an older one is refused (409). */
    record PatchRequest(@Min(1) int version, @NotEmpty @Size(max = 50) List<@Valid Operation> operations) {
    }

    // --- responses ---

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChangeView(String id, String section, String op, String path, Object before, Object after,
            String rationale, ChangeState state, boolean edited) {
    }

    /** {@code changeId} is the change that produced the flagged text, if any: reject it to clear the flag. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FlagView(String code, String severity, String path, String value, String message, String changeId) {
    }

    record FactCheckView(boolean passed, int blocking, int warnings, List<FlagView> flags, String checkerVersion) {
    }

    record JobRef(UUID id, String title, String company) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DraftResponse(UUID id, DocumentType type, DocumentStatus status, JobRef job, UUID baseResumeVersionId,
            String promptVersion, String model, int version, Map<String, Object> content, List<ChangeView> changes,
            FactCheckView factCheck, Instant createdAt, Instant updatedAt, Instant approvedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SummaryResponse(UUID id, DocumentType type, DocumentStatus status, JobRef job, int version,
            Integer blocking, Integer warnings, Instant createdAt, Instant updatedAt, Instant approvedAt) {
    }

    record ListResponse(List<SummaryResponse> items) {
    }
}
