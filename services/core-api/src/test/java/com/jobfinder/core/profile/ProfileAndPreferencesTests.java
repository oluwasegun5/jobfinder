package com.jobfinder.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The caller's profile and preferences: defaults, round trips, server-side validation and ownership. */
class ProfileAndPreferencesTests extends ResumeTestSupport {

    private static final String PROFILE = """
            {"fullName": "Ada Lovelace", "headline": "Engineer", "location": "London", "phone": "+44 20 7946 0000",
             "links": [{"label": "Site", "url": "https://ada.example.test"}], "yearsExperience": 7,
             "seniority": "SENIOR"}
            """;

    private static final String PREFERENCES = """
            {"targetTitles": ["Backend Engineer", "backend engineer", " Platform Engineer "],
             "locations": ["London", "Remote - EU"], "workModes": ["REMOTE", "HYBRID", "REMOTE"],
             "minSalary": 90000, "currency": "gbp", "needsSponsorship": true,
             "excludedCompanies": ["Acme"], "excludedIndustries": ["Gambling"]}
            """;

    private static String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    private ResultActions getJson(String path, Session session) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(session)));
    }

    private ResultActions putJson(String path, Session session, String body) throws Exception {
        return mvc.perform(put(path).header("Authorization", bearer(session)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // --- profile ---

    @Test
    void aNewUserHasAnEmptyProfileAndHasNotCompletedOnboarding() throws Exception {
        getJson("/profile", newSession()).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.links.length()").value(0))
                .andExpect(jsonPath("$.onboardingCompleted").value(false))
                // Absent, not null: the generated client types say "optional", and a null would read as text.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))));
    }

    @Test
    void theProfileRoundTripsAndASecondSaveReplacesTheFirst() throws Exception {
        Session session = newSession();

        putJson("/profile", session, PROFILE).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.seniority").value("SENIOR"))
                .andExpect(jsonPath("$.yearsExperience").value(7))
                .andExpect(jsonPath("$.links[0].url").value("https://ada.example.test"));
        putJson("/profile", session, "{\"fullName\": \"Ada King\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada King"))
                .andExpect(jsonPath("$.headline").doesNotExist())
                .andExpect(jsonPath("$.yearsExperience").doesNotExist())
                .andExpect(jsonPath("$.links.length()").value(0));

        assertThat(count("select count(*) from profiles where user_id = ?", userIdOf(session.accessToken())))
                .isEqualTo(1);
        getJson("/profile", session).andExpect(jsonPath("$.fullName").value("Ada King"));
    }

    @Test
    void theOwnerIsTheAuthenticatedUserNeverAnIdInTheBody() throws Exception {
        Session me = newSession();
        Session other = newSession();
        UUID otherId = userIdOf(other.accessToken());

        putJson("/profile", me, "{\"userId\": \"" + otherId + "\", \"user_id\": \"" + otherId
                + "\", \"fullName\": \"Mine\"}").andExpect(status().isOk());
        putJson("/preferences", me, "{\"userId\": \"" + otherId + "\"}").andExpect(status().isOk());

        assertThat(count("select count(*) from profiles where user_id = ?", otherId)).isZero();
        assertThat(count("select count(*) from preferences where user_id = ?", otherId)).isZero();
        getJson("/profile", other).andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.onboardingCompleted").value(false));
        getJson("/profile", me).andExpect(jsonPath("$.fullName").value("Mine"));
    }

    @Test
    void usersNeverSeeEachOthersProfileOrPreferences() throws Exception {
        Session ada = newSession();
        Session grace = newSession();
        putJson("/profile", ada, PROFILE).andExpect(status().isOk());
        putJson("/preferences", ada, PREFERENCES).andExpect(status().isOk());
        putJson("/profile", grace, "{\"fullName\": \"Grace Hopper\"}").andExpect(status().isOk());

        getJson("/profile", grace).andExpect(jsonPath("$.fullName").value("Grace Hopper"))
                .andExpect(jsonPath("$.headline").doesNotExist());
        getJson("/preferences", grace).andExpect(jsonPath("$.targetTitles.length()").value(0))
                .andExpect(jsonPath("$.minSalary").doesNotExist());
    }

    @Test
    void invalidProfilesAreRejectedServerSide() throws Exception {
        Session session = newSession();
        String[] bad = {
                "{\"fullName\": \"" + "n".repeat(201) + "\"}",
                "{\"headline\": \"" + "h".repeat(301) + "\"}",
                "{\"phone\": \"call me maybe\"}",
                "{\"yearsExperience\": -1}",
                "{\"yearsExperience\": 81}",
                "{\"seniority\": \"WIZARD\"}",
                "{\"links\": [{\"url\": \"javascript:alert(1)\"}]}",
                "{\"links\": [{\"url\": \"\"}]}",
                "{\"links\": [" + "{\"url\": \"https://a.example.test\"},".repeat(11)
                        + "{\"url\": \"https://a.example.test\"}]}",
                "[1, 2]",
        };
        for (String body : bad) {
            putJson("/profile", session, body).andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from profiles where user_id = ?", userIdOf(session.accessToken())))
                .isZero();
    }

    @Test
    void validationFailuresNameTheOffendingFieldsWithoutEchoingValues() throws Exception {
        putJson("/profile", newSession(), "{\"yearsExperience\": 200, \"links\": [{\"url\": \"javascript:alert(1)\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[?(@.field == 'yearsExperience')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'links[0].url')]").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alert"))));
    }

    // --- preferences ---

    @Test
    void aNewUserHasEmptyPreferences() throws Exception {
        getJson("/preferences", newSession()).andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTitles.length()").value(0))
                .andExpect(jsonPath("$.workModes.length()").value(0))
                .andExpect(jsonPath("$.needsSponsorship").value(false))
                .andExpect(jsonPath("$.minSalary").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))));
    }

    @Test
    void preferencesAreCleanedStoredAndReturned() throws Exception {
        Session session = newSession();

        putJson("/preferences", session, PREFERENCES).andExpect(status().isOk())
                // Trimmed, blank-free and de-duplicated ignoring case; the currency is upper-cased.
                .andExpect(jsonPath("$.targetTitles[0]").value("Backend Engineer"))
                .andExpect(jsonPath("$.targetTitles[1]").value("Platform Engineer"))
                .andExpect(jsonPath("$.targetTitles.length()").value(2))
                .andExpect(jsonPath("$.workModes[0]").value("REMOTE"))
                .andExpect(jsonPath("$.workModes[1]").value("HYBRID"))
                .andExpect(jsonPath("$.workModes.length()").value(2))
                .andExpect(jsonPath("$.minSalary").value(90000))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.needsSponsorship").value(true))
                .andExpect(jsonPath("$.excludedCompanies[0]").value("Acme"))
                .andExpect(jsonPath("$.excludedIndustries[0]").value("Gambling"));

        getJson("/preferences", session).andExpect(jsonPath("$.locations[1]").value("Remote - EU"));
        assertThat(count("select count(*) from preferences where user_id = ?", userIdOf(session.accessToken())))
                .isEqualTo(1);
    }

    @Test
    void savingPreferencesCompletesOnboardingAndSavingThemEmptyIsHowToSkip() throws Exception {
        Session session = newSession();
        getJson("/profile", session).andExpect(jsonPath("$.onboardingCompleted").value(false));

        putJson("/preferences", session, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTitles.length()").value(0));

        getJson("/profile", session).andExpect(jsonPath("$.onboardingCompleted").value(true));
    }

    @Test
    void invalidPreferencesAreRejectedServerSide() throws Exception {
        Session session = newSession();
        String[] bad = {
                "{\"workModes\": [\"MOON\"]}",
                "{\"minSalary\": -5, \"currency\": \"USD\"}",
                "{\"minSalary\": 100000001, \"currency\": \"USD\"}",
                // A floor needs a currency, and the currency must be a real ISO 4217 code.
                "{\"minSalary\": 50000}",
                "{\"minSalary\": 50000, \"currency\": \"ZZZ\"}",
                "{\"currency\": \"US\"}",
                "{\"currency\": \"DOLLARS\"}",
                "{\"targetTitles\": [\"" + "t".repeat(101) + "\"]}",
                "{\"targetTitles\": [" + manyDistinct(21) + "]}",
                "{\"locations\": [" + manyDistinct(21) + "]}",
                "{\"excludedCompanies\": [" + manyDistinct(51) + "]}",
                "{\"excludedIndustries\": [" + manyDistinct(31) + "]}",
        };
        for (String body : bad) {
            putJson("/preferences", session, body).andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from preferences where user_id = ?", userIdOf(session.accessToken())))
                .isZero();
        getJson("/profile", session).andExpect(jsonPath("$.onboardingCompleted").value(false));
    }

    private static String manyDistinct(int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            out.append(i == 0 ? "" : ",").append("\"item").append(i).append('"');
        }
        return out.toString();
    }

    // --- access control and cleanup ---

    @Test
    void theEndpointsRequireAuthentication() throws Exception {
        for (String path : new String[] { "/profile", "/preferences" }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void deletingTheAccountRemovesProfileAndPreferences() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        putJson("/profile", session, PROFILE).andExpect(status().isOk());
        putJson("/preferences", session, PREFERENCES).andExpect(status().isOk());

        mvc.perform(delete("/me").header("Authorization", bearer(session))).andExpect(status().is2xxSuccessful());

        assertThat(count("select count(*) from profiles where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from preferences where user_id = ?", userId)).isZero();
    }
}
