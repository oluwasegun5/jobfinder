package com.jobfinder.core.profile.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3-compatible object storage ({@code app.storage.*}): S3Mock locally, Cloudflare R2 in
 * production. {@code publicEndpoint} is the address browsers use in pre-signed URLs when it
 * differs from the one core-api itself talks to (e.g. the docker-compose network name).
 */
@ConfigurationProperties("app.storage")
record StorageProperties(
        String endpoint,
        String publicEndpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("jobfinder") String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("true") boolean pathStyle) {

    String presignEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    }
}
