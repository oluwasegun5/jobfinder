package com.jobfinder.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jobfinder.core.EndpointInventory.Endpoint;
import com.jobfinder.core.identity.AuthTestSupport;

/**
 * Work item 6, the endpoints that have no owner: every {@code /admin/**} mapping needs the ADMIN role and every
 * {@code /internal/**} mapping needs the service token, and the user-facing token never opens an internal one. The
 * mappings come from Spring (not a list), so a new admin or internal endpoint is held to the same rule automatically.
 */
class AdminAndInternalAccessGuardTests extends AuthTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyAdminEndpointRefusesAnonymousAndOrdinaryUsersButOpensForAnAdmin() throws Exception {
        List<Endpoint> admin = under("/admin/");
        assertThat(admin).as("admin endpoints found").isNotEmpty();
        String user = login(registerVerifiedUser(), PASSWORD, newIp()).accessToken();
        String adminEmail = registerVerifiedUser();
        jdbc.update("update users set role = 'ADMIN' where email = ?", adminEmail);
        String adminToken = login(adminEmail, PASSWORD, newIp()).accessToken();

        for (Endpoint endpoint : admin) {
            assertThat(call(endpoint, null).andReturn().getResponse().getStatus()).as(endpoint.key() + " anonymous")
                    .isEqualTo(401);
            assertThat(call(endpoint, user).andReturn().getResponse().getStatus()).as(endpoint.key() + " ordinary user")
                    .isEqualTo(403);
            assertThat(call(endpoint, adminToken).andReturn().getResponse().getStatus())
                    .as(endpoint.key() + " admin (any answer but 401 or 403: the request got through security)")
                    .isNotIn(401, 403);
        }
    }

    @Test
    void everyInternalEndpointNeedsTheServiceTokenAndNoUserTokenOpensIt() throws Exception {
        List<Endpoint> internal = under("/internal/");
        assertThat(internal).as("internal endpoints found").isNotEmpty();
        String user = login(registerVerifiedUser(), PASSWORD, newIp()).accessToken();
        String adminEmail = registerVerifiedUser();
        jdbc.update("update users set role = 'ADMIN' where email = ?", adminEmail);
        String adminToken = login(adminEmail, PASSWORD, newIp()).accessToken();

        for (Endpoint endpoint : internal) {
            assertThat(call(endpoint, null).andReturn().getResponse().getStatus()).as(endpoint.key() + " anonymous")
                    .isEqualTo(401);
            assertThat(call(endpoint, user).andReturn().getResponse().getStatus()).as(endpoint.key() + " user JWT")
                    .isEqualTo(401);
            assertThat(call(endpoint, adminToken).andReturn().getResponse().getStatus()).as(endpoint.key() + " admin JWT")
                    .isEqualTo(401);
            assertThat(callWithServiceToken(endpoint, "wrong-" + UUID.randomUUID()).andReturn().getResponse().getStatus())
                    .as(endpoint.key() + " wrong service token").isEqualTo(401);
            assertThat(callWithServiceToken(endpoint, "").andReturn().getResponse().getStatus())
                    .as(endpoint.key() + " empty service token").isEqualTo(401);
        }
    }

    @Test
    void theInternalAndAdminSetsAreWhatTheMappingsSay() {
        // A guard on the guard: if the enumeration silently found nothing, the two tests above would prove nothing.
        List<String> internal = under("/internal/").stream().map(Endpoint::key).toList();
        assertThat(internal).contains("PUT /internal/v1/billing/usage", "POST /internal/v1/embeddings/backfill");
        assertThat(under("/admin/").stream().map(Endpoint::key).collect(Collectors.toList()))
                .contains("GET /admin/billing/costs", "GET /admin/ingestion/sources");
    }

    private List<Endpoint> under(String prefix) {
        return EndpointInventory.all(handlerMapping).stream().filter(e -> e.pattern().startsWith(prefix)).toList();
    }

    private org.springframework.test.web.servlet.ResultActions call(Endpoint endpoint, String bearer) throws Exception {
        MockHttpServletRequestBuilder request = build(endpoint);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions callWithServiceToken(Endpoint endpoint, String token)
            throws Exception {
        return mvc.perform(build(endpoint).header("X-Service-Token", token));
    }

    private static MockHttpServletRequestBuilder build(Endpoint endpoint) {
        String path = endpoint.pattern().replaceAll("\\{[^}]+}", "x");
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(endpoint.method()), path);
        if (!endpoint.method().equals("GET")) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return request;
    }
}
