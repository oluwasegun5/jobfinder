package com.jobfinder.core.matching;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** GET /jobs/{id}/match: who may call it, the typed errors, and the shape of the answer. */
class MatchEndpointTests extends MatchingTestSupport {

    @Test
    void itRequiresASignedInUser() throws Exception {
        mvc.perform(get("/jobs/" + UUID.randomUUID() + "/match")).andExpect(status().isUnauthorized());
    }

    @Test
    void aUserWithoutAParsedResumeGetsAConflict() throws Exception {
        Session me = newSession();
        UUID id = insert(spec().title("A").embedding(unit(0)));

        getAs(me, "/jobs/" + id + "/match").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resume_required"));
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        Session me = newSession();
        seedFor(userIdOf(me), 0, "Backend engineer", "java");

        getAs(me, "/jobs/" + UUID.randomUUID() + "/match").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
    }

    @Test
    void aModelScoredMatchCarriesTheScoreStrengthsAndGaps() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        seedFor(user, 0, "Backend engineer", "java");
        UUID id = job("A", 5, "java");
        stubScores(user, Map.of(id, 81));

        getAs(me, "/jobs/" + id + "/match").andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("LLM_SCORED"))
                .andExpect(jsonPath("$.score").value(81))
                .andExpect(jsonPath("$.llmScore").value(81))
                .andExpect(jsonPath("$.stage2Score").isNumber())
                .andExpect(jsonPath("$.strengths[0]").isString())
                .andExpect(jsonPath("$.gaps[0]").isString())
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.promptVersion").value(PROMPT))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void whenAiServiceIsDownTheAnswerIsStillOkAndFlagged() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        seedFor(user, 0, "Backend engineer", "java");
        UUID id = job("A", 5, "java");
        stubStatus(user, 503, "{\"code\":\"llm_unavailable\"}");

        getAs(me, "/jobs/" + id + "/match").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_LLM_SCORED"))
                .andExpect(jsonPath("$.reason").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.llmScore").doesNotExist())
                .andExpect(jsonPath("$.score").isNumber());
    }
}
