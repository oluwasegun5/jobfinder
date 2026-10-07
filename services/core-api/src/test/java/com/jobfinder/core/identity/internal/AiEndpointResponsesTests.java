package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.identity.AuthTestSupport;

/**
 * The public API description says what the AI endpoints answer when a limit stops them (429 for the rate limit and the
 * daily cap, 402 for an empty balance), so the generated web client can type it. The endpoints are the AI class of the
 * rate-limit table, so no list is repeated here; and what is not an AI endpoint gets neither.
 */
class AiEndpointResponsesTests extends AuthTestSupport {

    private DocumentContext spec() throws Exception {
        return JsonPath.parse(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    @Test
    void everyAiEndpointDocuments429AndThoseThatSpendCreditsDocument402() throws Exception {
        DocumentContext spec = spec();
        List<EndpointClassifier.Rule> ai = EndpointClassifier.RULES.stream()
                .filter(rule -> rule.endpointClass() == EndpointClass.AI).toList();
        assertThat(ai).as("AI endpoints in the rate-limit table").hasSizeGreaterThan(8);

        for (EndpointClassifier.Rule rule : ai) {
            String operation = "$.paths['" + rule.pattern() + "']." + rule.method().name().toLowerCase();
            assertThat(spec.read(operation + ".responses['429']", Object.class)).as(rule + " 429").isNotNull();
            assertThat(spec.read(operation + ".responses['429'].content['application/problem+json'].schema.$ref",
                    String.class)).isEqualTo("#/components/schemas/ProblemDetail");
            if (rule.pattern().equals("/jobs/{id}/match")) {
                // A spent balance gives the heuristic score, not a 402.
                assertThat(spec.read(operation + ".responses", java.util.Map.class)).doesNotContainKey("402");
            } else {
                assertThat(spec.read(operation + ".responses['402']", Object.class)).as(rule + " 402").isNotNull();
            }
        }
        assertThat(spec.read("$.components.schemas.ProblemDetail.properties", java.util.Map.class))
                .containsKeys("code", "status", "detail", "resetsAt");
    }

    @Test
    void otherEndpointsAreLeftAlone() throws Exception {
        DocumentContext spec = spec();

        assertThat(spec.read("$.paths['/billing/me'].get.responses", java.util.Map.class)).doesNotContainKeys("402",
                "429");
        assertThat(spec.read("$.paths['/feed'].get.responses", java.util.Map.class)).doesNotContainKey("402");
    }

    @Test
    void theSpecHasNoActuatorInfoAndNoWebhooks() throws Exception {
        DocumentContext spec = spec();

        assertThat(spec.read("$.paths", java.util.Map.class)).doesNotContainKey("/actuator/info")
                .containsKey("/actuator/health").containsKey("/admin/billing/users/{userId}/adjustments");
        assertThat(spec.read("$.paths", java.util.Map.class).keySet().stream()
                .filter(path -> path.toString().startsWith("/webhooks") || path.toString().startsWith("/internal")))
                .isEmpty();
    }
}
