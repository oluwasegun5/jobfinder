package com.jobfinder.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a test method as the ownership test of the listed endpoints, each as {@code "METHOD /path/{pattern}"}
 * exactly as Spring maps it (for example {@code "GET /documents/{id}"}). The test must prove that a second user cannot read
 * or change the first user's resource (404 or 403, as the module's convention says) or, for a collection or a
 * per-user setting, that a second user sees only their own.
 *
 * <p>{@link EndpointOwnershipGuardTests} reads these annotations from the test classpath and fails for every controller
 * endpoint that is neither registered here nor on its explicit allow-list of public or ownerless endpoints.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CoversEndpoints {

    String[] value();
}
