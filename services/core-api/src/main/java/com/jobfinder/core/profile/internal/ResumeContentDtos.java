package com.jobfinder.core.profile.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The structured content of a resume, in the exact JSON shape ai-service's parser produces and
 * {@code resume_versions.structured} stores (schema version 1, snake_case via explicit {@code @JsonProperty} so the
 * OpenAPI spec matches the wire), so one shape runs from parser to
 * database to editor and back. The limits mirror ai-service's {@code ParsedResume}. Everything is validated here,
 * server-side: the editor's own checks are a convenience only.
 */
final class ResumeContentDtos {

    static final String DATE = "^\\d{4}(-(0[1-9]|1[0-2]))?$";
    static final String HTTP_URL = "^(?i)https?://\\S+$";
    static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private ResumeContentDtos() {
    }

    /** A link; only http(s) URLs are accepted, so a stored link can never be a {@code javascript:} URL. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Link(
            @Size(max = 200) String label,
            @NotBlank @Size(max = 500) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String url) {

        Link {
            label = CleanText.orNull(label);
            url = CleanText.orEmpty(url);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Contact(
            @Size(max = 200) @JsonProperty("full_name") String fullName,
            @Size(max = 200) @Pattern(regexp = EMAIL, message = "must be an email address") String email,
            @Size(max = 200) String phone,
            @Size(max = 200) String location,
            @Size(max = 10) List<@Valid Link> links) {

        Contact {
            fullName = CleanText.orNull(fullName);
            email = CleanText.orNull(email);
            phone = CleanText.orNull(phone);
            location = CleanText.orNull(location);
            links = CleanText.nonNull(links);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Experience(
            @NotBlank @Size(max = 200) String company,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 200) String location,
            @Pattern(regexp = DATE, message = "must be YYYY or YYYY-MM") @JsonProperty("start_date") String startDate,
            @Pattern(regexp = DATE, message = "must be YYYY or YYYY-MM") @JsonProperty("end_date") String endDate,
            @JsonProperty("is_current") Boolean isCurrent,
            @Size(max = 25) List<@NotBlank @Size(max = 2000) String> bullets) {

        Experience {
            company = CleanText.orEmpty(company);
            title = CleanText.orEmpty(title);
            location = CleanText.orNull(location);
            startDate = CleanText.orNull(startDate);
            endDate = CleanText.orNull(endDate);
            isCurrent = Boolean.TRUE.equals(isCurrent);
            bullets = CleanText.listKeepingDuplicates(bullets);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Education(
            @NotBlank @Size(max = 200) String institution,
            @Size(max = 200) String degree,
            @Size(max = 200) @JsonProperty("field_of_study") String fieldOfStudy,
            @Pattern(regexp = DATE, message = "must be YYYY or YYYY-MM") @JsonProperty("start_date") String startDate,
            @Pattern(regexp = DATE, message = "must be YYYY or YYYY-MM") @JsonProperty("end_date") String endDate) {

        Education {
            institution = CleanText.orEmpty(institution);
            degree = CleanText.orNull(degree);
            fieldOfStudy = CleanText.orNull(fieldOfStudy);
            startDate = CleanText.orNull(startDate);
            endDate = CleanText.orNull(endDate);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Project(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Size(max = 500) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String url,
            @Size(max = 25) List<@NotBlank @Size(max = 200) String> technologies) {

        Project {
            name = CleanText.orEmpty(name);
            description = CleanText.orNull(description);
            url = CleanText.orNull(url);
            technologies = CleanText.list(technologies);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Certification(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String issuer,
            @Pattern(regexp = DATE, message = "must be YYYY or YYYY-MM") String date) {

        Certification {
            name = CleanText.orEmpty(name);
            issuer = CleanText.orNull(issuer);
            date = CleanText.orNull(date);
        }
    }

    /** The whole structured CV. {@code schemaVersion} is set by the server; any value sent is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResumeContent(
            @JsonProperty("schema_version") Integer schemaVersion,
            @Valid Contact contact,
            @Size(max = 200) String headline,
            @Size(max = 2000) String summary,
            @Size(max = 30) List<@Valid Experience> experience,
            @Size(max = 15) List<@Valid Education> education,
            @Size(max = 100) List<@NotBlank @Size(max = 100) String> skills,
            @Size(max = 20) List<@Valid Project> projects,
            @Size(max = 20) List<@Valid Certification> certifications) {

        static final int SCHEMA_VERSION = 1;

        ResumeContent {
            schemaVersion = SCHEMA_VERSION;
            contact = contact == null ? new Contact(null, null, null, null, null) : contact;
            headline = CleanText.orNull(headline);
            summary = CleanText.orNull(summary);
            experience = CleanText.nonNull(experience);
            education = CleanText.nonNull(education);
            skills = CleanText.list(skills);
            projects = CleanText.nonNull(projects);
            certifications = CleanText.nonNull(certifications);
        }
    }

    /** A grounding warning from the parser: {@code path} such as {@code experience[0].company}, and a stable code. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ParseWarning(String path, String code) {
    }

    /**
     * The latest content of a resume plus what the review screen needs to decide what to show. {@code content} is
     * null until parsing has filled it (or the user saves something). {@code warnings} belong to the parser's own
     * output and are empty once the user has saved an edit.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResumeContentResponse(UUID resumeId, ParseStatus parseStatus, String parseError, int versionNumber,
            ResumeVersion.Source source, ResumeContent content, List<ParseWarning> warnings, Instant updatedAt) {
    }
}
