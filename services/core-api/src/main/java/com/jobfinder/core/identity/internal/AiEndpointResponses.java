package com.jobfinder.core.identity.internal;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * Documents what the AI endpoints answer when a limit stops them, so the generated web client can type it: 429
 * {@code rate_limited} (the {@link EndpointClass#AI} bucket, with {@code Retry-After}) or {@code ai_daily_cap_reached}
 * (with {@code resetsAt}), and 402 {@code insufficient_credits} for the endpoints that spend credits. The set of
 * endpoints is the AI class of {@link EndpointClassifier}, the same table the rate limit uses, so a new AI endpoint is
 * documented as soon as it is classified. {@code GET /jobs/{id}/match} falls back to a heuristic score on an empty
 * balance instead of answering 402, so it gets the 429 only.
 */
@Component
class AiEndpointResponses implements OpenApiCustomizer {

    static final String PROBLEM = "ProblemDetail";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSchemas(PROBLEM, problemSchema());
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            if (EndpointClassifier.classify(method.name(), path) != EndpointClass.AI) {
                return;
            }
            if (operation.getResponses() == null) {
                operation.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses());
            }
            if (!(method.name().equals("GET") && path.equals("/jobs/{id}/match"))) {
                operation.getResponses().putIfAbsent("402", problem(
                        "The user's credit balance is spent (code insufficient_credits); a plan grant or a top-up restores it."));
            }
            operation.getResponses().putIfAbsent("429", problem(
                    "Too many requests (code rate_limited, with Retry-After) or today's AI allowance is used up "
                            + "(code ai_daily_cap_reached, with resetsAt and Retry-After)."));
        }));
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType(PROBLEM_JSON,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
    }

    /** RFC 7807 as core-api writes it: the standard members plus the stable {@code code} (and {@code resetsAt}). */
    private static Schema<?> problemSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("type", new StringSchema());
        schema.addProperty("title", new StringSchema());
        schema.addProperty("status", new IntegerSchema());
        schema.addProperty("detail", new StringSchema());
        schema.addProperty("instance", new StringSchema());
        schema.addProperty("code", new StringSchema().description("Stable, machine-readable error code"));
        schema.addProperty("resetsAt", new StringSchema().description("ISO-8601 UTC; only for ai_daily_cap_reached"));
        return schema;
    }
}
