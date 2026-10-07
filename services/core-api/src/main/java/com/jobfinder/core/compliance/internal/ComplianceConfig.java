package com.jobfinder.core.compliance.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.jobfinder.core.compliance.RetentionProperties;

@Configuration
@EnableConfigurationProperties(RetentionProperties.class)
class ComplianceConfig {
}
