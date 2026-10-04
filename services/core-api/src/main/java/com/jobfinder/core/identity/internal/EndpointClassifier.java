package com.jobfinder.core.identity.internal;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;

/**
 * Maps a handler mapping (HTTP method and the best-matching path pattern, for example {@code POST /jobs/{id}/tailor})
 * to its {@link EndpointClass}. The explicit rules list the endpoints that cost something or hand out files; anything
 * else falls into a catch-all class by method. A test enumerates the real Spring handler mappings and fails when a
 * rule names an endpoint that no longer exists, so the table cannot rot silently.
 */
final class EndpointClassifier {

    record Rule(HttpMethod method, String pattern, EndpointClass endpointClass) {
    }

    private static Rule get(String pattern, EndpointClass c) {
        return new Rule(HttpMethod.GET, pattern, c);
    }

    private static Rule post(String pattern, EndpointClass c) {
        return new Rule(HttpMethod.POST, pattern, c);
    }

    /** Explicit rules, matched on the exact pattern string of the handler mapping. */
    static final List<Rule> RULES = List.of(
            // AI-calling.
            post("/jobs/{id}/tailor", EndpointClass.AI),
            post("/jobs/{id}/cover-letter", EndpointClass.AI),
            post("/jobs/{id}/screening-answers", EndpointClass.AI),
            post("/jobs/{id}/application-pack", EndpointClass.AI),
            post("/application-packs/{id}/retry", EndpointClass.AI),
            post("/interview-prep", EndpointClass.AI),
            post("/interview-sessions", EndpointClass.AI),
            post("/interview-sessions/{id}/answers", EndpointClass.AI),
            post("/interview-sessions/{id}/complete", EndpointClass.AI),
            post("/applications/{id}/follow-up-draft", EndpointClass.AI),
            post("/resumes/{id}/reparse", EndpointClass.AI),
            get("/jobs/{id}/match", EndpointClass.AI),
            // Upload and download.
            post("/resumes", EndpointClass.UPLOAD),
            get("/resumes/{id}/download-url", EndpointClass.DOWNLOAD),
            get("/documents/{id}/files/{fileId}/download", EndpointClass.DOWNLOAD),
            // Export.
            post("/documents/{id}/render", EndpointClass.EXPORT),
            post("/resumes/{id}/render", EndpointClass.EXPORT),
            // Search.
            get("/jobs", EndpointClass.SEARCH),
            get("/jobs/{id}/similar", EndpointClass.SEARCH),
            get("/feed", EndpointClass.SEARCH),
            // Extension.
            get("/extension/apply-context", EndpointClass.EXTENSION),
            // Admin actions that start outbound fetches.
            post("/admin/ingestion/sources/{code}/runs", EndpointClass.ADMIN_ACTION),
            post("/admin/ingestion/targets", EndpointClass.ADMIN_ACTION),
            // Signed links, no sign-in.
            get("/notifications/unsubscribe/{token}", EndpointClass.PUBLIC_LINK),
            post("/notifications/unsubscribe/{token}", EndpointClass.PUBLIC_LINK),
            // Limited elsewhere, with the reason in EndpointClass.EXEMPT.
            post("/billing/checkout", EndpointClass.EXEMPT),
            post("/billing/subscription/cancel", EndpointClass.EXEMPT));

    private static final Map<String, EndpointClass> BY_KEY = RULES.stream().collect(
            java.util.stream.Collectors.toMap(r -> key(r.method(), r.pattern()), Rule::endpointClass));

    private EndpointClassifier() {
    }

    static EndpointClass classify(String method, String pattern) {
        if (pattern == null) {
            return EndpointClass.API_WRITE;
        }
        if (pattern.startsWith("/auth/") && !pattern.equals("/auth/me") || pattern.startsWith("/internal/")
                || pattern.startsWith("/webhooks/") || pattern.startsWith("/actuator")) {
            return EndpointClass.EXEMPT;
        }
        EndpointClass explicit = BY_KEY.get(method.toUpperCase(java.util.Locale.ROOT) + " " + pattern);
        if (explicit != null) {
            return explicit;
        }
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
                ? EndpointClass.API_READ : EndpointClass.API_WRITE;
    }

    private static String key(HttpMethod method, String pattern) {
        return method.name() + " " + pattern;
    }
}
