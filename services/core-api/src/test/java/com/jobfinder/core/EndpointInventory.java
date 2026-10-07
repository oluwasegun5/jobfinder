package com.jobfinder.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every controller endpoint, read from Spring's own handler mappings (never from a hand-kept list): one row per HTTP
 * method and path pattern, with the handler method. The rate-limit table and the ownership guard are checked against it.
 */
public final class EndpointInventory {

    public record Endpoint(String method, String pattern, HandlerMethod handler) implements Comparable<Endpoint> {

        /** {@code "GET /jobs/{id}"}, the key the guard tests use. */
        public String key() {
            return method + " " + pattern;
        }

        @Override
        public int compareTo(Endpoint other) {
            return Comparator.comparing(Endpoint::pattern).thenComparing(Endpoint::method).compare(this, other);
        }
    }

    private EndpointInventory() {
    }

    /** All endpoints of the application's own controllers (springdoc, actuator and the error controller excluded). */
    public static List<Endpoint> all(RequestMappingHandlerMapping mapping) {
        Set<Endpoint> endpoints = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.jobfinder.core")) {
                return;
            }
            for (String pattern : patterns(info)) {
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    endpoints.add(new Endpoint("ANY", pattern, handler));
                }
                for (RequestMethod method : methods) {
                    endpoints.add(new Endpoint(method.name(), pattern, handler));
                }
            }
        });
        return new ArrayList<>(endpoints);
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return Set.of();
    }
}
