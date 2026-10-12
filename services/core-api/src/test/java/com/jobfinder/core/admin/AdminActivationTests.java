package com.jobfinder.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jobfinder.core.jobs.JobViews;
import com.jobfinder.core.jobs.JobsTestSupport;

/**
 * The activation funnel for admins (docs/adr/0045-activation-funnel.md): ADMIN only, exact counts and conversion per
 * step and per signup week, trial outcome aggregates, and nothing that identifies a user. The seeded cohorts sit in
 * March 2020, far from anything the other tests create, so the figures are exact.
 */
class AdminActivationTests extends JobsTestSupport {

    /** Monday 2 March to Sunday 15 March 2020: two signup weeks. */
    private static final String RANGE = "?from=2020-03-02&to=2020-03-15";

    private final List<UUID> seeded = new ArrayList<>();

    @Autowired
    private JobViews views;

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        jdbc.update("delete from users where created_at < '2021-01-01' and email like 'funnel-%'");
        seeded.clear();
    }

    // --- seeding: rows exactly as the owning modules leave them ---

    private UUID user(String createdAt, boolean verified, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, email_verified_at, role, created_at, updated_at)
                values (?, ?, case when ? then ?::timestamptz end, ?, ?::timestamptz, ?::timestamptz)
                """, id, "funnel-" + id + "@example.test", verified, createdAt, role, createdAt, createdAt);
        seeded.add(id);
        return id;
    }

    private UUID user(String createdAt, boolean verified) {
        return user(createdAt, verified, "USER");
    }

    private void resume(UUID userId, String parseStatus) {
        jdbc.update("""
                insert into resumes (id, user_id, label, file_key, file_type, size_bytes, is_primary, parse_status,
                                     created_at, updated_at)
                values (?, ?, 'cv', ?, 'PDF', 10, true, ?, now(), now())
                """, UUID.randomUUID(), userId, "resumes/" + userId + ".pdf", parseStatus);
    }

    private void preferences(UUID userId) {
        jdbc.update("insert into preferences (id, user_id, created_at, updated_at) values (?, ?, now(), now())",
                UUID.randomUUID(), userId);
    }

    private void viewed(UUID userId) {
        views.recordFeedView(userId, List.of(insert("Funnel " + UUID.randomUUID())), Instant.now());
    }

    private void tailored(UUID userId, String status) {
        jdbc.update("""
                insert into generated_documents (id, user_id, type, status, job_id, job_title, base_resume_version_id,
                                                 prompt_version, source_content, content, created_at, updated_at)
                values (?, ?, 'TAILORED_RESUME', ?, ?, 'Engineer', ?, 'v1', '{}'::jsonb,
                        case when ? = 'GENERATING' then null else '{}'::jsonb end, now(), now())
                """, UUID.randomUUID(), userId, status, UUID.randomUUID(), UUID.randomUUID(), status);
    }

    private void trial(UUID userId, String state) {
        jdbc.update("insert into trial_accounts (user_id, state, milestones, created_at, updated_at) "
                + "values (?, ?, 0, now(), now())", userId, state);
    }

    /**
     * Week of 2 March: u1 reaches the last step, u2 stops before a tailored CV (a GENERATING draft does not count), u3's
     * CV failed to parse, u4 never verified, u8 is an account from before the trial. Week of 9 March: u5 saved
     * preferences without a CV (so the funnel stops at email verified), u6 got as far as preferences, u7 never
     * verified but has a draft (a later step without the earlier ones is not counted). Outside the range, an admin,
     * and a fully activated user on each side of the range.
     */
    private void seed() {
        UUID u1 = user("2020-03-03T10:00:00Z", true);
        resume(u1, "PARSED");
        preferences(u1);
        viewed(u1);
        tailored(u1, "DRAFT");
        trial(u1, "GRANTED");

        UUID u2 = user("2020-03-04T10:00:00Z", true);
        resume(u2, "PARSED");
        preferences(u2);
        viewed(u2);
        tailored(u2, "GENERATING");
        trial(u2, "GRANTED");

        UUID u3 = user("2020-03-05T10:00:00Z", true);
        resume(u3, "FAILED");
        trial(u3, "GRANTED");

        user("2020-03-06T10:00:00Z", false);

        UUID u8 = user("2020-03-07T10:00:00Z", true);
        trial(u8, "LEGACY");

        UUID u5 = user("2020-03-10T10:00:00Z", true);
        preferences(u5);
        trial(u5, "DENIED");

        UUID u6 = user("2020-03-11T10:00:00Z", true);
        resume(u6, "PARSED");
        preferences(u6);
        trial(u6, "HOLD");

        UUID u7 = user("2020-03-12T10:00:00Z", false);
        tailored(u7, "DRAFT");

        // Out of scope: an admin inside the range, and activated users just before and just after it.
        UUID admin = user("2020-03-04T11:00:00Z", true, "ADMIN");
        resume(admin, "PARSED");
        for (String at : new String[] {"2020-03-01T23:59:59Z", "2020-03-16T00:00:00Z"}) {
            UUID outside = user(at, true);
            resume(outside, "PARSED");
            preferences(outside);
            viewed(outside);
            tailored(outside, "DRAFT");
            trial(outside, "GRANTED");
        }
    }

    private String token(boolean admin) throws Exception {
        String email = registerVerifiedUser();
        if (admin) {
            jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        }
        return login(email, PASSWORD, newIp()).accessToken();
    }

    private ResultActions activation(String query, String bearer) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/activation" + query);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    // --- authorization ---

    @Test
    void noTokenIsUnauthorizedAndASignedInUserIsForbidden() throws Exception {
        activation(RANGE, null).andExpect(status().isUnauthorized());
        activation(RANGE, token(false)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    // --- the numbers ---

    @Test
    void anAdminSeesTheCountAndConversionOfEveryStepForTheCohort() throws Exception {
        seed();

        activation(RANGE, token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2020-03-02"))
                .andExpect(jsonPath("$.to").value("2020-03-15"))
                .andExpect(jsonPath("$.steps.length()").value(7))
                .andExpect(jsonPath("$.steps[0].step").value("SIGNUP"))
                .andExpect(jsonPath("$.steps[0].count").value(8))
                .andExpect(jsonPath("$.steps[0].fromPrevious").doesNotExist())
                .andExpect(jsonPath("$.steps[0].overall").value(1.0))
                .andExpect(jsonPath("$.steps[1].step").value("EMAIL_VERIFIED"))
                .andExpect(jsonPath("$.steps[1].count").value(6))
                .andExpect(jsonPath("$.steps[1].fromPrevious").value(0.75))
                .andExpect(jsonPath("$.steps[1].overall").value(0.75))
                .andExpect(jsonPath("$.steps[2].step").value("CV_UPLOADED"))
                .andExpect(jsonPath("$.steps[2].count").value(4))
                .andExpect(jsonPath("$.steps[2].fromPrevious").value(0.6667))
                .andExpect(jsonPath("$.steps[2].overall").value(0.5))
                .andExpect(jsonPath("$.steps[3].step").value("CV_PARSED"))
                .andExpect(jsonPath("$.steps[3].count").value(3))
                .andExpect(jsonPath("$.steps[3].fromPrevious").value(0.75))
                .andExpect(jsonPath("$.steps[3].overall").value(0.375))
                .andExpect(jsonPath("$.steps[4].step").value("ONBOARDING_COMPLETED"))
                .andExpect(jsonPath("$.steps[4].count").value(3))
                .andExpect(jsonPath("$.steps[4].fromPrevious").value(1.0))
                .andExpect(jsonPath("$.steps[5].step").value("FIRST_MATCHES_VIEWED"))
                .andExpect(jsonPath("$.steps[5].count").value(2))
                .andExpect(jsonPath("$.steps[5].fromPrevious").value(0.6667))
                .andExpect(jsonPath("$.steps[6].step").value("FIRST_TAILORED_CV"))
                .andExpect(jsonPath("$.steps[6].count").value(1))
                .andExpect(jsonPath("$.steps[6].fromPrevious").value(0.5))
                .andExpect(jsonPath("$.steps[6].overall").value(0.125));
    }

    @Test
    void theSameCountsAreGivenPerSignupWeek() throws Exception {
        seed();

        activation(RANGE, token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(2))
                .andExpect(jsonPath("$.cohorts[0].weekStart").value("2020-03-02"))
                .andExpect(jsonPath("$.cohorts[0].counts[0]").value(5))
                .andExpect(jsonPath("$.cohorts[0].counts[1]").value(4))
                .andExpect(jsonPath("$.cohorts[0].counts[2]").value(3))
                .andExpect(jsonPath("$.cohorts[0].counts[3]").value(2))
                .andExpect(jsonPath("$.cohorts[0].counts[4]").value(2))
                .andExpect(jsonPath("$.cohorts[0].counts[5]").value(2))
                .andExpect(jsonPath("$.cohorts[0].counts[6]").value(1))
                .andExpect(jsonPath("$.cohorts[1].weekStart").value("2020-03-09"))
                .andExpect(jsonPath("$.cohorts[1].counts[0]").value(3))
                .andExpect(jsonPath("$.cohorts[1].counts[1]").value(2))
                .andExpect(jsonPath("$.cohorts[1].counts[2]").value(1))
                .andExpect(jsonPath("$.cohorts[1].counts[3]").value(1))
                .andExpect(jsonPath("$.cohorts[1].counts[4]").value(1))
                .andExpect(jsonPath("$.cohorts[1].counts[5]").value(0))
                .andExpect(jsonPath("$.cohorts[1].counts[6]").value(0));
    }

    @Test
    void theTrialOutcomesAreCountedForTheCohortWithoutLegacyAccounts() throws Exception {
        seed();

        activation(RANGE, token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.trial.granted").value(3))
                .andExpect(jsonPath("$.trial.hold").value(1))
                .andExpect(jsonPath("$.trial.denied").value(1));
    }

    @Test
    void theResponseIdentifiesNobody() throws Exception {
        seed();

        String body = activation(RANGE, token(true)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("funnel-", "@example.test", "userId", "riskScore");
        for (UUID id : seeded) {
            assertThat(body).doesNotContain(id.toString());
        }
    }

    @Test
    void anEmptyRangeHasZeroCountsAndNoConversion() throws Exception {
        activation("?from=2019-01-07&to=2019-01-13", token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].count").value(0))
                .andExpect(jsonPath("$.steps[0].overall").doesNotExist())
                .andExpect(jsonPath("$.steps[1].fromPrevious").doesNotExist())
                .andExpect(jsonPath("$.cohorts.length()").value(0))
                .andExpect(jsonPath("$.trial.granted").value(0));
    }

    @Test
    void withoutARangeTheLastThirtyDaysIncludingTodayAreShown() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);

        activation("", token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.from").value(today.minusDays(29).toString()));
    }

    @Test
    void anInvertedOrTooLongRangeIsRefused() throws Exception {
        String admin = token(true);

        activation("?from=2020-03-10&to=2020-03-01", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_range"));
        activation("?from=2018-01-01&to=2020-03-01", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_range"));
    }

    // --- a real user, through every milestone ---

    @Test
    void aUserDrivenThroughEveryMilestoneIsCountedAtEveryStep() throws Exception {
        // Signup and email verification through the real endpoints, preferences through the real API, the feed view
        // through the public JobViews service; the CV and the tailored draft as the profile and documents modules
        // leave them (parsing and tailoring need the AI service).
        Session session = newSession();
        UUID userId = userIdOf(session);
        jdbc.update("update users set created_at = '2019-06-05T09:00:00Z' where id = ?", userId);
        String admin = token(true);
        String day = "?from=2019-06-03&to=2019-06-09";

        assertSteps(activation(day, admin), 1, 1, 0, 0, 0, 0, 0);

        resume(userId, "PENDING");
        assertSteps(activation(day, admin), 1, 1, 1, 0, 0, 0, 0);

        jdbc.update("update resumes set parse_status = 'PARSED' where user_id = ?", userId);
        assertSteps(activation(day, admin), 1, 1, 1, 1, 0, 0, 0);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/preferences")
                .header("Authorization", bearer(session)).contentType("application/json")
                .content("{\"targetTitles\":[\"Engineer\"],\"locations\":[\"Lagos\"],\"workModes\":[]}"))
                .andExpect(status().isOk());
        assertSteps(activation(day, admin), 1, 1, 1, 1, 1, 0, 0);

        viewed(userId);
        assertSteps(activation(day, admin), 1, 1, 1, 1, 1, 1, 0);

        tailored(userId, "DRAFT");
        assertSteps(activation(day, admin), 1, 1, 1, 1, 1, 1, 1);
    }

    private static void assertSteps(ResultActions result, int... counts) throws Exception {
        result.andExpect(status().isOk());
        for (int i = 0; i < counts.length; i++) {
            result.andExpect(jsonPath("$.steps[" + i + "].count").value(counts[i]));
        }
    }
}
