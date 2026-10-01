package com.jobfinder.core.matching.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ MatchingProperties.class, MatchAiServiceProperties.class })
class MatchingConfig {
}
