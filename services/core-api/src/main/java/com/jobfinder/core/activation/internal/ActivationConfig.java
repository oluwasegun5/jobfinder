package com.jobfinder.core.activation.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.jobfinder.core.activation.IdentityDigester;

/**
 * The identity digester. {@code app.trial-identity.current-key-version} picks the pepper new claims are written with;
 * the peppers themselves are the {@code TRIAL_PEPPER_V<n>} environment variables (docs/adr/0042).
 */
@Configuration
class ActivationConfig {

    @Bean
    IdentityDigester identityDigester(Environment environment,
            @Value("${app.trial-identity.current-key-version:1}") short currentKeyVersion) {
        if (currentKeyVersion < 1) {
            throw new IllegalStateException("app.trial-identity.current-key-version must be 1 or more");
        }
        return PepperKeyring.fromEnvironment(environment, currentKeyVersion);
    }
}
