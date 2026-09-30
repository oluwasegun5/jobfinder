package com.jobfinder.core.profile;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jobfinder.core.AiServiceStubs;

/** Reading the parsed CV, and saving the user's reviewed (or hand-written) version of it. */
class ResumeContentTests extends ResumeParsingTestSupport {

    private static final String VALID = """
            {
              "contact": {"full_name": "Ada Lovelace", "email": "ada@example.test", "phone": "+44 20 7946 0000",
                          "location": "London", "links": [{"label": "Site", "url": "https://ada.example.test"}]},
              "headline": "Engineer",
              "summary": "Writes programs.",
              "experience": [{"company": "Analytical Engines", "title": "Programmer", "start_date": "2020-01",
                              "end_date": null, "is_current": true, "bullets": ["Wrote the first program."]}],
              "education": [{"institution": "Home", "degree": "BSc", "field_of_study": "Maths",
                             "start_date": "2010", "end_date": "2013"}],
              "skills": ["Math", "math", "Poetry"],
              "projects": [{"name": "Notes", "description": "A translation", "url": "https://notes.example.test",
                            "technologies": ["Ink"]}],
              "certifications": [{"name": "Badge", "issuer": "Society", "date": "2015-06"}]
            }
            """;

    private static String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    private ResultActions getContent(Session session, UUID id) throws Exception {
        return mvc.perform(get("/resumes/" + id + "/content").header("Authorization", bearer(session)));
    }

    private ResultActions putContent(Session session, UUID id, String body) throws Exception {
        return mvc.perform(put("/resumes/" + id + "/content").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Uploads a CV for a session whose parse is stubbed with the given ai-service body. */
    private UUID parsedResume(Session session, String aiServiceBody) throws Exception {
        stubParse(userIdOf(session.accessToken()), okJson(aiServiceBody));
        UUID id = uploadPdf(session);
        awaitStatus(id, "PARSED");
        return id;
    }

    @Test
    void aParsedResumeReturnsItsContentAndGroundingWarnings() throws Exception {
        Session session = newSession();
        String withWarning = AiServiceStubs.parseOkBody().replace("\"warnings\": []",
                "\"warnings\": [{\"path\": \"experience[1].company\", \"code\": \"not_in_source\"}]");
        UUID id = parsedResume(session, withWarning);

        getContent(session, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.parseStatus").value("PARSED"))
                .andExpect(jsonPath("$.source").value("UPLOAD"))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.content.contact.full_name").value("Jordan Reyes"))
                .andExpect(jsonPath("$.content.experience.length()").value(3))
                .andExpect(jsonPath("$.content.experience[0].is_current").value(true))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].path").value("experience[1].company"))
                .andExpect(jsonPath("$.warnings[0].code").value("not_in_source"));
    }

    @Test
    void malformedWarningsAreDroppedWithoutFailingTheParse() throws Exception {
        Session session = newSession();
        String body = AiServiceStubs.parseOkBody().replace("\"warnings\": []",
                "\"warnings\": [{\"path\": 5}, \"x\", {\"path\": \"skills[0]\", \"code\": \"skill_not_in_source\"}]");
        UUID id = parsedResume(session, body);

        getContent(session, id).andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].path").value("skills[0]"));
    }

    @Test
    void aPendingResumeHasNoContentYet() throws Exception {
        Session session = newSession();
        UUID id = uploadPdf(session);
        // Hold the state at PENDING regardless of the background parser.
        jdbc.update("update resumes set parse_status = 'PENDING', parse_error = null where id = ?", id);
        jdbc.update("update resume_versions set structured = null where resume_id = ?", id);

        getContent(session, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.parseStatus").value("PENDING"))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.warnings.length()").value(0));
    }

    @Test
    void aFailedResumeReportsWhyAndCanStillBeFilledInByHand() throws Exception {
        Session session = newSession();
        stubParse(userIdOf(session.accessToken()), problem(422, "no_extractable_text", false));
        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        getContent(session, id).andExpect(jsonPath("$.parseStatus").value("FAILED"))
                .andExpect(jsonPath("$.parseError").value("no_extractable_text"))
                .andExpect(jsonPath("$.content").doesNotExist());

        putContent(session, id, VALID).andExpect(status().isOk())
                .andExpect(jsonPath("$.parseStatus").value("FAILED"))
                .andExpect(jsonPath("$.source").value("EDIT"))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.content.contact.full_name").value("Ada Lovelace"));
    }

    @Test
    void savingAddsAnEditVersionAndLeavesTheParsersVersionUntouched() throws Exception {
        Session session = newSession();
        UUID id = parsedResume(session, AiServiceStubs.parseOkBody());
        String parsed = structured(id, 1);

        putContent(session, id, VALID).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("EDIT"))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.warnings.length()").value(0))
                // Cleaned on the way in: duplicate skill dropped case-insensitively.
                .andExpect(jsonPath("$.content.skills.length()").value(2));

        assertThat(versionCount(id)).isEqualTo(2);
        assertThat(structured(id, 1)).isEqualTo(parsed);
        // Stored in the same snake_case shape the parser writes, with the version set by the server.
        assertThat(jdbc.queryForObject("select structured ->> 'schema_version' from resume_versions "
                + "where resume_id = ? and version_number = 2", String.class, id)).isEqualTo("1");
        assertThat(jdbc.queryForObject("select structured -> 'experience' -> 0 ->> 'is_current' from resume_versions "
                + "where resume_id = ? and version_number = 2", String.class, id)).isEqualTo("true");
        getContent(session, id).andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.content.headline").value("Engineer"));
    }

    @Test
    void savingAgainUpdatesTheEditVersionInPlace() throws Exception {
        Session session = newSession();
        UUID id = parsedResume(session, AiServiceStubs.parseOkBody());

        putContent(session, id, VALID).andExpect(status().isOk());
        putContent(session, id, VALID.replace("\"Engineer\"", "\"Staff Engineer\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(2));

        assertThat(versionCount(id)).isEqualTo(2);
        getContent(session, id).andExpect(jsonPath("$.content.headline").value("Staff Engineer"));
    }

    @Test
    void aParseThatFinishesAfterAnEditNeverReplacesTheEdit() throws Exception {
        Session session = newSession();
        UUID id = uploadPdf(session);
        awaitStatus(id, "PARSED");
        // Rewind to the moment of a slow parse: nothing parsed yet, the user already saved.
        jdbc.update("update resumes set parse_status = 'PENDING' where id = ?", id);
        jdbc.update("update resume_versions set structured = null, parse_warnings = null where resume_id = ?", id);

        putContent(session, id, VALID).andExpect(status().isOk());

        getContent(session, id).andExpect(jsonPath("$.source").value("EDIT"))
                .andExpect(jsonPath("$.content.contact.full_name").value("Ada Lovelace"));
    }

    @Test
    void anotherUsersResumeIsNotFoundForBothReadAndWrite() throws Exception {
        Session owner = newSession();
        Session intruder = newSession();
        UUID id = parsedResume(owner, AiServiceStubs.parseOkBody());
        String before = structured(id, 1);

        getContent(intruder, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("resume_not_found"));
        putContent(intruder, id, VALID).andExpect(status().isNotFound());
        getContent(owner, UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(versionCount(id)).isEqualTo(1);
        assertThat(structured(id, 1)).isEqualTo(before);
    }

    @Test
    void contentEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/resumes/" + UUID.randomUUID() + "/content")).andExpect(status().isUnauthorized());
        mvc.perform(put("/resumes/" + UUID.randomUUID() + "/content").contentType(MediaType.APPLICATION_JSON)
                .content(VALID)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidContentIsRejectedAndNothingIsStored() throws Exception {
        Session session = newSession();
        UUID id = parsedResume(session, AiServiceStubs.parseOkBody());

        String[] bad = {
                // Links must be http(s): never a javascript: URL.
                VALID.replace("https://ada.example.test", "javascript:alert(1)"),
                VALID.replace("https://notes.example.test", "ftp://notes.example.test"),
                VALID.replace("\"2020-01\"", "\"January 2020\""),
                VALID.replace("\"ada@example.test\"", "\"not-an-email\""),
                VALID.replace("\"Analytical Engines\"", "\"   \""),
                VALID.replace("\"Engineer\"", "\"" + "x".repeat(201) + "\""),
                VALID.replace("\"skills\": [\"Math\", \"math\", \"Poetry\"]",
                        "\"skills\": [\"" + "y".repeat(101) + "\"]"),
                // A current role has no end date; an end cannot precede its start.
                VALID.replace("\"end_date\": null", "\"end_date\": \"2021-01\""),
                VALID.replace("\"is_current\": true", "\"is_current\": false").replace("\"end_date\": null",
                        "\"end_date\": \"2019-12\""),
                VALID.replace("\"end_date\": \"2013\"", "\"end_date\": \"2009\""),
                "not json",
        };
        for (String body : bad) {
            putContent(session, id, body).andExpect(status().isBadRequest());
        }
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @Test
    void tooManyItemsAreRejected() throws Exception {
        Session session = newSession();
        UUID id = parsedResume(session, AiServiceStubs.parseOkBody());
        String manySkills = "{\"skills\": [" + String.join(",", java.util.Collections.nCopies(101, "\"a\"")) + "]}";
        // 101 identical skills collapse to one: the cap applies to distinct, cleaned entries.
        putContent(session, id, manySkills).andExpect(status().isOk());
        StringBuilder distinct = new StringBuilder("{\"skills\": [");
        for (int i = 0; i < 101; i++) {
            distinct.append(i == 0 ? "" : ",").append("\"skill").append(i).append('"');
        }
        putContent(session, id, distinct.append("]}").toString()).andExpect(status().isBadRequest());
    }

    @Test
    void controlCharactersAreStrippedSoTheyCannotBreakStorage() throws Exception {
        Session session = newSession();
        UUID id = parsedResume(session, AiServiceStubs.parseOkBody());

        putContent(session, id, "{\"headline\": \"Dev\\u0000eloper\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.headline").value("Dev eloper"));
    }

    @Test
    void anEmptyBodyObjectSavesEmptyContent() throws Exception {
        Session session = newSession();
        UUID id = parsedResume(session, AiServiceStubs.parseOkBody());

        putContent(session, id, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.experience.length()").value(0))
                .andExpect(jsonPath("$.content.skills.length()").value(0))
                .andExpect(jsonPath("$.content.contact.links.length()").value(0));
    }

    @Test
    void deletingTheResumeRemovesItsEditVersionsToo() throws Exception {
        Session session = newSession();
        UUID id = parsedResume(session, AiServiceStubs.parseOkBody());
        putContent(session, id, VALID).andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/resumes/" + id)
                .header("Authorization", bearer(session))).andExpect(status().isNoContent());

        assertThat(versionCount(id)).isZero();
    }
}
