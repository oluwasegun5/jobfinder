package com.jobfinder.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jobfinder.core.EndpointInventory.Endpoint;

/**
 * The ownership guard (work item 6). It enumerates every controller handler mapping from Spring itself and fails for any
 * endpoint that is neither
 *
 * <ol>
 * <li>on the explicit allow-list below of public or ownerless endpoints, each with a one-line reason, nor</li>
 * <li>registered with {@link CoversEndpoints} on a test that proves another user cannot read or change the resource (or
 * sees only their own, for collections and per-user settings).</li>
 * </ol>
 *
 * A new endpoint therefore cannot ship without one or the other. The admin and {@code /internal} endpoints are covered
 * by prefix, because {@link AdminAndInternalAccessGuardTests} asserts the role or service token for every mapping under
 * those prefixes (it enumerates them the same way), so a new one there is checked automatically.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EndpointOwnershipGuardTests {

    /** Public or ownerless endpoints and why no ownership test applies. */
    static final Map<String, String> ALLOW_LIST = Map.ofEntries(
            Map.entry("POST /auth/signup", "pre-authentication: creates the caller's own account, no resource id"),
            Map.entry("POST /auth/verify-email", "pre-authentication: bound to a single-use random token"),
            Map.entry("POST /auth/resend-verification", "pre-authentication: always 202, reveals nothing about the address"),
            Map.entry("POST /auth/login", "pre-authentication: credentials in the body, same error for unknown and wrong"),
            Map.entry("POST /auth/google", "pre-authentication: Google ID token verified for issuer and audience"),
            Map.entry("POST /auth/refresh", "cookie-bound: the refresh token names the session, rotation is tested in RefreshAndLogoutTests"),
            Map.entry("POST /auth/logout", "cookie-bound: revokes only the presented token's family (RefreshAndLogoutTests)"),
            Map.entry("POST /auth/forgot-password", "pre-authentication: always 202, reveals nothing about the address"),
            Map.entry("POST /auth/reset-password", "pre-authentication: bound to a single-use random token"),
            Map.entry("GET /jobs/{id}/similar", "shared job catalog: jobs are public to every signed-in user, no per-user data returned"),
            Map.entry("GET /billing/plans", "public plan and pack catalog, identical for everyone"));

    /** Prefixes whose every mapping is asserted dynamically by AdminAndInternalAccessGuardTests and WebhookSignatureTests. */
    static final Map<String, String> ALLOW_LIST_PREFIXES = Map.of(
            "/admin/", "ADMIN role required: asserted for every mapping by AdminAndInternalAccessGuardTests",
            "/internal/", "service token required, user JWTs refused: asserted for every mapping by AdminAndInternalAccessGuardTests",
            "/webhooks/", "provider signature required: StripeWebhookTests and PaystackWebhookTests");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyEndpointIsOwnerlessOnTheAllowListOrHasARegisteredOwnershipTest() {
        Set<String> covered = registeredCoverage().keySet();
        List<String> unguarded = EndpointInventory.all(handlerMapping).stream().map(Endpoint::key)
                .filter(key -> !ALLOW_LIST.containsKey(key))
                .filter(key -> ALLOW_LIST_PREFIXES.keySet().stream().noneMatch(prefix -> key.contains(" " + prefix)))
                .filter(key -> !covered.contains(key)).toList();

        assertThat(unguarded)
                .as("endpoints with neither an ownership test (@CoversEndpoints) nor an allow-list entry")
                .isEmpty();
    }

    @Test
    void everyRegisteredOrAllowListedKeyNamesARealEndpointSoTheRegistryCannotRot() {
        Set<String> real = EndpointInventory.all(handlerMapping).stream().map(Endpoint::key)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(registeredCoverage().keySet().stream().filter(key -> !real.contains(key)).toList())
                .as("@CoversEndpoints names an endpoint that does not exist").isEmpty();
        assertThat(ALLOW_LIST.keySet().stream().filter(key -> !real.contains(key)).toList())
                .as("allow-list entry for an endpoint that does not exist").isEmpty();
    }

    @Test
    void anAllowListedEndpointNeverTakesAnIdOfAnOwnedResource() {
        // A public endpoint with a resource id in the path would be an IDOR waiting to happen: only the token-bound auth
        // endpoints and the shared catalog may be allow-listed, and the catalog is a reviewed, fixed list.
        List<String> withId = ALLOW_LIST.keySet().stream().filter(key -> key.contains("{")).toList();
        assertThat(withId).containsExactly("GET /jobs/{id}/similar");
    }

    @Test
    void noGetHandlerCanChangeStateThroughTheCookieSoACrossSiteGetIsHarmless() {
        // CSRF (work item 3): cookie-authenticated requests are POSTs only. A GET handler that read the refresh cookie
        // could be driven by an image tag, so none may take a @CookieValue or be the cookie endpoints.
        for (Endpoint endpoint : EndpointInventory.all(handlerMapping)) {
            if (!endpoint.method().equals("GET")) {
                continue;
            }
            assertThat(endpoint.pattern()).as(endpoint.key()).isNotIn("/auth/refresh", "/auth/logout");
            for (var parameter : endpoint.handler().getMethodParameters()) {
                assertThat(parameter.hasParameterAnnotation(CookieValue.class)).as(endpoint.key()).isFalse();
            }
        }
        // The one reviewed GET that acts on a signed link is read-only: it only describes (UnsubscribeTests.getOnlyDescribesAndPostDoesIt).
    }

    /** Endpoint key to the test methods that register it, read from the test classpath. */
    static Map<String, List<String>> registeredCoverage() {
        Map<String, List<String>> coverage = new HashMap<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> reader.getClassMetadata().getClassName().endsWith("Tests"));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.jobfinder.core")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName(), false,
                        EndpointOwnershipGuardTests.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            for (Method method : type.getDeclaredMethods()) {
                CoversEndpoints annotation = method.getAnnotation(CoversEndpoints.class);
                if (annotation != null) {
                    for (String key : annotation.value()) {
                        coverage.computeIfAbsent(key, k -> new java.util.ArrayList<>())
                                .add(type.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }
        return coverage;
    }
}
