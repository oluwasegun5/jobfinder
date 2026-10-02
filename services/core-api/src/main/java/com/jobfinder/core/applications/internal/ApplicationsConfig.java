package com.jobfinder.core.applications.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ ApplicationsProperties.class, ApplicationsAiProperties.class })
class ApplicationsConfig {
}
