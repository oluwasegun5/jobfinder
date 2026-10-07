package com.jobfinder.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ASVS 7.4 and 14.3: error bodies carry no stack traces, class names or SQL, the actuator offers health only, and the
 * API description and Swagger UI exist in development but not under the production profile (ADR 0037).
 */
class InformationExposureTests {

    private static final String[] LEAKS = { "Exception", "org.springframework", "java.lang", "com.jobfinder", "\tat ",
            "SQLState", "PSQL", "select ", "jdbc", "hibernate", "Caused by" };

    static void assertClean(String body) {
        for (String leak : LEAKS) {
            assertThat(body).as("error body must not contain '%s'", leak).doesNotContain(leak);
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(TestcontainersConfiguration.class)
    abstract static class Base {

        @Autowired
        MockMvc mvc;
    }

    @Nested
    class Development extends Base {

        @Test
        void errorBodiesAreProblemDetailsWithoutInternals() throws Exception {
            for (var request : new org.springframework.test.web.servlet.RequestBuilder[] {
                    get("/no/such/endpoint"),
                    get("/jobs/not-a-uuid"),
                    post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{broken"),
                    post("/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"),
                    get("/auth/me").header("Authorization", "Bearer not.a.token"),
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/auth/login") }) {
                String body = mvc.perform(request).andReturn().getResponse().getContentAsString();
                assertClean(body);
            }
        }

        @Test
        void theActuatorOffersHealthOnly() throws Exception {
            mvc.perform(get("/actuator/info")).andExpect(status().is4xxClientError());
            mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
            mvc.perform(get("/actuator/beans")).andExpect(status().is4xxClientError());
            mvc.perform(get("/actuator/heapdump")).andExpect(status().is4xxClientError());
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }

    @Nested
    @ActiveProfiles("prod")
    // The test context has provider keys and realistic prices (TestcontainersConfiguration); production also refuses
    // placeholder provider plan ids (BillingPriceGuard), so this profile gets real-looking ones.
    @TestPropertySource(properties = { "STRIPE_PRICE_PRO_USD=price_1RealProUsd", "PAYSTACK_PLAN_PRO_NGN=PLN_realpro" })
    class Production extends Base {

        @Test
        void theApiDescriptionAndTheSwaggerUiAreNotServed() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isNotFound());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
            mvc.perform(get("/swagger-ui.html")).andExpect(status().is4xxClientError());
        }

        @Test
        void healthIsUpOrDownWithNoDetail() throws Exception {
            String body = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            assertThat(body).contains("UP").doesNotContain("components").doesNotContain("postgres")
                    .doesNotContain("redis").doesNotContain("rabbit");
        }
    }
}
