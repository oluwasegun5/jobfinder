package com.jobfinder.core.ingestion.internal.aggregator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AggregatorProperties.class)
class AggregatorConfig {
}
