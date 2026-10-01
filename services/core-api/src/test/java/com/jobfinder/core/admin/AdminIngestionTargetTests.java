package com.jobfinder.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.identity.AuthTestSupport;

/**
 * POST /admin/ingestion/targets: admin only, idempotent, and it files the board's jobs under a company.
 * Authentication is real (signup, email verification, login); an admin is a user whose role was set in the
 * database, which is also how the bootstrap admin comes to be.
 */
class AdminIngestionTargetTests extends AuthTestSupport {

    private static final String PATH = "/admin/ingestion/targets";

    private String token(boolean admin) throws Exception {
        String email = registerVerifiedUser();
        if (admin) {
            jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        }
        return login(email, PASSWORD, newIp()).accessToken();
    }

    private ResultActions add(String bearer, String json) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(json);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private static String body(String source, String identifier, String company) {
        return "{\"source\":\"%s\",\"identifier\":\"%s\",\"companyName\":\"%s\"}".formatted(source, identifier, company);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void requiresAuthentication() throws Exception {
        add(null, body("GREENHOUSE", unique("board"), "Acme")).andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedInNonAdminIsForbiddenAndNothingIsAdded() throws Exception {
        String userToken = token(false);
        String board = unique("board");

        add(userToken, body("GREENHOUSE", board, "Acme")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        assertThat(count("select count(*) from source_targets where identifier = ?", board)).isZero();
    }

    @Test
    void anAdminAddsATargetAndRepeatingItChangesNothing() throws Exception {
        String adminToken = token(true);
        String board = unique("board");
        String company = unique("Acme Robotics");

        String created = add(adminToken, body("GREENHOUSE", board, company)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("GREENHOUSE")).andExpect(jsonPath("$.identifier").value(board))
                .andExpect(jsonPath("$.companyName").value(company)).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.created").value(true)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        add(adminToken, body("GREENHOUSE", board, "A Different Name")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.companyName").value(company));

        assertThat(count("select count(*) from source_targets where identifier = ?", board)).isEqualTo(1);
        assertThat(count("select count(*) from companies where name = ?", company)).isEqualTo(1);
        assertThat(count("""
                select count(*) from source_targets t join companies c on c.id = t.company_id
                where t.identifier = ? and c.name = ?""", board, company)).isEqualTo(1);
    }

    @Test
    void aDisabledTargetStaysDisabledWhenAddedAgain() throws Exception {
        String adminToken = token(true);
        String board = unique("board");
        add(adminToken, body("LEVER", board, "Acme")).andExpect(status().isCreated());
        jdbc.update("update source_targets set enabled = false where identifier = ?", board);

        add(adminToken, body("LEVER", board, "Acme")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(count("select count(*) from source_targets where identifier = ? and enabled", board)).isZero();
    }

    @Test
    void theSameCompanyNameSharesOneCompanyAcrossSources() throws Exception {
        String adminToken = token(true);
        String company = unique("Shared Name");

        add(adminToken, body("GREENHOUSE", unique("a"), company)).andExpect(status().isCreated());
        add(adminToken, body("ASHBY", unique("b"), company + ", Inc.")).andExpect(status().isCreated());

        assertThat(count("select count(*) from companies where name like ?", company + "%")).isEqualTo(1);
    }

    @Test
    void anAggregatorSearchTargetNeedsNoCompany() throws Exception {
        String adminToken = token(true);
        String search = unique("gb:software engineer");
        try {
            String created = add(adminToken, "{\"source\":\"ARBEITNOW\",\"identifier\":\"%s\"}".formatted(search))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("ARBEITNOW"))
                    .andExpect(jsonPath("$.identifier").value(search)).andExpect(jsonPath("$.companyName").doesNotExist())
                    .andExpect(jsonPath("$.created").value(true)).andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(created, "$.id");

            add(adminToken, "{\"source\":\"ARBEITNOW\",\"identifier\":\"%s\"}".formatted(search))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.created").value(false));

            add(adminToken, body("ARBEITNOW", unique("all"), "Acme")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_target"));
        } finally {
            jdbc.update("delete from source_targets where identifier = ?", search);
        }
    }

    @Test
    void anUnknownSourceIsNotFound() throws Exception {
        String adminToken = token(true);

        add(adminToken, body("NOPE", "board", "Acme")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("unknown_source"));
        add(adminToken, body("greenhouse", "board", "Acme")).andExpect(status().isNotFound());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        String adminToken = token(true);

        add(adminToken, body("GREENHOUSE", "board", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_target"));
        add(adminToken, "{\"source\":\"GREENHOUSE\",\"identifier\":\"board\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_target"));
        add(adminToken, body("GREENHOUSE", "", "Acme")).andExpect(status().isBadRequest());
        add(adminToken, "{}").andExpect(status().isBadRequest());
        add(adminToken, body("GREENHOUSE", "line\\nbreak", "Acme")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_target"));
        add(adminToken, body("GREENHOUSE", "board", "!!!")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_target"));
        add(adminToken, body("GREENHOUSE", "x".repeat(256), "Acme")).andExpect(status().isBadRequest());
    }
}
