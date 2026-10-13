package com.jobfinder.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.identity.AuthTestSupport;

/**
 * The trial review queue API (docs/adr/0047-trial-review-queue.md): ADMIN only, a masked email and the gate's reason
 * codes only, approve releasing the grant once, deny final, every decision audited with the admin who took it.
 */
class AdminTrialsTests extends AuthTestSupport {

    private static final String NOTE = "{\"note\":\"Looked at it: a genuine signup\"}";

    private String token(boolean admin) throws Exception {
        String email = registerVerifiedUser();
        if (admin) {
            jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        }
        return login(email, PASSWORD, newIp()).accessToken();
    }

    private UUID adminId(String token) throws Exception {
        return UUID.fromString(JsonPath.read(
                getMe(token).andReturn().getResponse().getContentAsString(), "$.id"));
    }

    /** A verified user with a trial account in {@code state} and the given reasons, as the gate would leave it. */
    private UUID account(String state, String reasonsJson) throws Exception {
        String email = registerVerifiedUser();
        UUID id = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        jdbc.update("""
                insert into trial_accounts (user_id, state, milestones, milestones_reached, risk_score, decision_reasons,
                                            started_at, expires_at, created_at, updated_at)
                values (?, ?, 0, 1, 30, cast(? as jsonb), now(), case when ? = 'HOLD' then now() + interval '30 days' end,
                        now(), now())
                """, id, state, reasonsJson, state);
        return id;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions postNote(String path, String body, String bearer) throws Exception {
        return send(post(path).contentType(MediaType.APPLICATION_JSON).content(body), bearer);
    }

    @Test
    void noTokenIsUnauthorizedAndASignedInUserIsForbiddenOnEveryTrialEndpoint() throws Exception {
        UUID id = account("DENIED", "[]");
        String user = token(false);
        String[] paths = {"/admin/trials", "/admin/trials/" + id};

        for (String path : paths) {
            send(get(path), null).andExpect(status().isUnauthorized());
            send(get(path), user).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
        }
        for (String action : new String[] {"approve", "deny"}) {
            String path = "/admin/trials/" + id + "/" + action;
            postNote(path, NOTE, null).andExpect(status().isUnauthorized());
            postNote(path, NOTE, user).andExpect(status().isForbidden());
        }
        assertThat(count("select count(*) from trial_review_decisions where user_id = ?", id)).isZero();
        assertThat(count("select count(*) from trial_accounts where user_id = ? and state = 'DENIED' "
                + "and review_decision is null", id)).isEqualTo(1);
    }

    @Test
    void theQueueShowsReasonsAndAMaskedEmailAndNothingThatIdentifiesTheMatch() throws Exception {
        String reasons = "[{\"code\":\"cv-phone\",\"points\":30,\"hard\":false},"
                + "{\"code\":\"disposable-domain\",\"points\":0,\"hard\":true}]";
        UUID id = account("DENIED", reasons);
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, id);
        String admin = token(true);

        String body = send(get("/admin/trials?state=DENIED&size=100"), admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> found = JsonPath.read(body, "$.items[?(@.userId=='" + id + "')]");
        assertThat(found).hasSize(1);
        Map<String, Object> item = found.get(0);
        assertThat(item).containsEntry("state", "DENIED").containsEntry("reviewStatus", "PENDING")
                .containsEntry("riskScore", 30);
        assertThat(item.get("reasons")).asList().containsExactly(
                Map.of("code", "cv-phone", "points", 30, "hard", false),
                Map.of("code", "disposable-domain", "points", 0, "hard", true));
        assertThat(item.get("milestonesReached")).asList().containsExactly("M1");

        String local = email.substring(0, email.indexOf('@'));
        assertThat(body).contains(local.charAt(0) + "***" + email.substring(email.indexOf('@')));
        assertThat(body).doesNotContain(email).doesNotContain(local);
        assertThat(body.toLowerCase()).doesNotContain("digest").doesNotContain("pepper").doesNotContain("hash");
    }

    @Test
    void theQueueFiltersByStateAndPages() throws Exception {
        UUID held = account("HOLD", "[]");
        UUID denied = account("DENIED", "[]");
        String admin = token(true);

        String onlyHeld = send(get("/admin/trials?state=HOLD&size=100"), admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(onlyHeld).contains(held.toString()).doesNotContain(denied.toString());

        send(get("/admin/trials?size=1&page=0"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    @Test
    void badFiltersAndPagesAreRejected() throws Exception {
        String admin = token(true);

        send(get("/admin/trials?state=MAYBE"), admin).andExpect(status().isBadRequest());
        send(get("/admin/trials?view=EVERYTHING"), admin).andExpect(status().isBadRequest());
        send(get("/admin/trials?size=0"), admin).andExpect(status().isBadRequest());
        send(get("/admin/trials?size=101"), admin).andExpect(status().isBadRequest());
        send(get("/admin/trials?page=-1"), admin).andExpect(status().isBadRequest());
    }

    @Test
    void theDetailShowsTheDecisionsTakenWithTheNote() throws Exception {
        UUID id = account("DENIED", "[]");
        String admin = token(true);
        postNote("/admin/trials/" + id + "/approve", NOTE, admin).andExpect(status().isOk());

        send(get("/admin/trials/" + id), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.item.userId").value(id.toString()))
                .andExpect(jsonPath("$.item.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.decisions.length()").value(1))
                .andExpect(jsonPath("$.decisions[0].action").value("APPROVE"))
                .andExpect(jsonPath("$.decisions[0].note").value("Looked at it: a genuine signup"));
        send(get("/admin/trials/" + UUID.randomUUID()), admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("trial_not_found"));
    }

    @Test
    void approvingGrantsOnceAuditsTheAdminAndIsIdempotent() throws Exception {
        UUID id = account("DENIED", "[]");
        String admin = token(true);
        UUID adminUser = adminId(admin);

        postNote("/admin/trials/" + id + "/approve", NOTE, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.creditsGranted").value(30))
                .andExpect(jsonPath("$.item.state").value("GRANTED"))
                .andExpect(jsonPath("$.item.reviewStatus").value("APPROVED"));
        postNote("/admin/trials/" + id + "/approve", NOTE, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.creditsGranted").value(0));

        assertThat(jdbc.queryForObject("select coalesce(sum(delta), 0) from credit_ledger where user_id = ? "
                + "and reason = 'TRIAL_GRANT'", BigDecimal.class, id)).isEqualByComparingTo("30");
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_GRANT' "
                + "and idempotency_key = ?", id, "trial:" + id + ":M1")).isEqualTo(1);
        assertThat(count("select count(*) from trial_review_decisions where user_id = ? and admin_id = ? "
                + "and action = 'APPROVE' and note like 'Looked at it%'", id, adminUser)).isEqualTo(1);
        assertThat(count("select count(*) from trial_review_decisions where user_id = ?", id)).isEqualTo(1);
    }

    @Test
    void denyingIsFinal() throws Exception {
        UUID id = account("HOLD", "[]");
        String admin = token(true);

        postNote("/admin/trials/" + id + "/deny", "{\"note\":\"Same person as an earlier account\"}", admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.item.state").value("DENIED"))
                .andExpect(jsonPath("$.item.reviewStatus").value("DENIED"));
        postNote("/admin/trials/" + id + "/approve", NOTE, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trial_denied_final"));
        postNote("/admin/trials/" + id + "/deny", "{\"note\":\"Again\"}", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false));

        assertThat(count("select count(*) from trial_accounts where user_id = ? and state = 'DENIED' "
                + "and review_decision = 'DENIED'", id)).isEqualTo(1);
        assertThat(count("select count(*) from trial_review_decisions where user_id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_GRANT'", id)).isZero();
    }

    @Test
    void aDecisionNeedsANonBlankNoteOfAtMost1000Characters() throws Exception {
        UUID id = account("DENIED", "[]");
        String admin = token(true);
        String path = "/admin/trials/" + id;

        for (String action : new String[] {"approve", "deny"}) {
            postNote(path + "/" + action, "{}", admin).andExpect(status().isBadRequest());
            postNote(path + "/" + action, "{\"note\":\"\"}", admin).andExpect(status().isBadRequest());
            postNote(path + "/" + action, "{\"note\":\"    \"}", admin).andExpect(status().isBadRequest());
            postNote(path + "/" + action, "{\"note\":\"" + "x".repeat(1001) + "\"}", admin)
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from trial_review_decisions where user_id = ?", id)).isZero();
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_GRANT'", id)).isZero();
    }

    @Test
    void anUnknownAccountIsNotFoundAndAnAccountNeverHeldIsNotInReview() throws Exception {
        String admin = token(true);
        UUID granted = account("GRANTED", "[]");

        postNote("/admin/trials/" + UUID.randomUUID() + "/approve", NOTE, admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("trial_not_found"));
        postNote("/admin/trials/" + granted + "/approve", NOTE, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trial_not_in_review"));
        postNote("/admin/trials/" + granted + "/deny", NOTE, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trial_not_in_review"));
    }

    @Test
    void anApprovedAccountCannotBeDeniedAndAnEndedTrialCannotBeApproved() throws Exception {
        String admin = token(true);
        UUID approved = account("DENIED", "[]");
        postNote("/admin/trials/" + approved + "/approve", NOTE, admin).andExpect(status().isOk());
        UUID ended = account("HOLD", "[]");
        jdbc.update("update trial_accounts set expires_at = now() - interval '1 day' where user_id = ?", ended);

        postNote("/admin/trials/" + approved + "/deny", NOTE, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trial_already_approved"));
        postNote("/admin/trials/" + ended + "/approve", NOTE, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trial_ended"));
    }
}
