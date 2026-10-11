package com.jobfinder.core.profile.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.jobfinder.core.TestcontainersConfiguration;
import com.jobfinder.core.activation.IdentityDigester;
import com.jobfinder.core.activation.IdentityNormaliser;
import com.jobfinder.core.activation.OnboardingCompleted;
import com.jobfinder.core.activation.ResumeParsed;
import com.jobfinder.core.profile.internal.ProfileDtos.PreferencesRequest;

/**
 * Profile publishes milestones M2 ({@link ResumeParsed}, digests computed here so no raw CV value leaves the module)
 * and M3 ({@link OnboardingCompleted}, once, when preferences are first saved).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class TrialMilestoneProfileEventTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ResumeParseStore parseStore;

    @Autowired
    private PreferencesService preferences;

    @Autowired
    private IdentityDigester digester;

    @Autowired
    private ApplicationEvents events;

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified_at, created_at, updated_at) "
                + "values (?, ?, now(), now(), now())", id, "p-" + id + "@example.test");
        return id;
    }

    private UUID resume(UUID userId, byte[] sha) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into resumes (id, user_id, label, file_key, file_type, size_bytes, is_primary, parse_status,
                                     created_at, updated_at, content_sha256)
                values (?, ?, 'cv', ?, 'PDF', 10, true, 'PENDING', now(), now(), ?)
                """, id, userId, "resumes/" + userId + "/" + id + ".pdf", sha);
        jdbc.update("insert into resume_versions (id, resume_id, version_number, source, created_at, updated_at) "
                + "values (?, ?, 1, 'UPLOAD', now(), now())", UUID.randomUUID(), id);
        return id;
    }

    @Test
    void aCompletedParsePublishesResumeParsedWithDigestsOnly() {
        UUID userId = user();
        byte[] sha = IdentityNormaliser.fileSha256(("cv-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        UUID resumeId = resume(userId, sha);
        String structured = """
                {"schema_version": 1, "contact": {"full_name": "Ada Example", "email": "Ada.Example+cv@Gmail.com",
                 "phone": "0803 123 4567, +44 20 7946 0958"}, "skills": ["java"]}
                """;

        assertThat(parseStore.complete(resumeId, 1, structured, "[]", "test-model", "v1")).isTrue();

        List<ResumeParsed> published = events.stream(ResumeParsed.class).toList();
        assertThat(published).hasSize(1);
        ResumeParsed event = published.get(0);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.file().current().value()).isEqualTo(digester.file(sha).current().value());
        assertThat(event.cvEmails()).singleElement().satisfies(e -> assertThat(e.current().value())
                .isEqualTo(digester.email("adaexample@gmail.com").orElseThrow().current().value()));
        assertThat(event.cvPhones()).hasSize(2);
        assertThat(event.toString()).doesNotContain("gmail", "0803", "Ada");
    }

    @Test
    void aParseThatChangesNothingPublishesNothing() {
        UUID userId = user();
        UUID resumeId = resume(userId, null);
        parseStore.complete(resumeId, 1, "{\"schema_version\": 1}", "[]", "m", "v1");

        assertThat(parseStore.complete(resumeId, 1, "{\"schema_version\": 1}", "[]", "m", "v1")).isFalse();

        assertThat(events.stream(ResumeParsed.class).toList()).singleElement()
                .satisfies(e -> {
                    assertThat(e.file()).isNull();
                    assertThat(e.cvEmails()).isEmpty();
                });
    }

    @Test
    void onboardingCompletesOnceWhenPreferencesAreFirstSaved() {
        UUID userId = user();
        PreferencesRequest request = new PreferencesRequest(List.of("Engineer"), List.of("Lagos"), List.of(), null,
                null, false, List.of(), List.of());

        preferences.save(userId, request);
        preferences.save(userId, request);

        assertThat(events.stream(OnboardingCompleted.class).toList()).singleElement()
                .satisfies(e -> assertThat(e.userId()).isEqualTo(userId));
    }
}
