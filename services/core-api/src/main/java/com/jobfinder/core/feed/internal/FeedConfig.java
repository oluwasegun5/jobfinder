package com.jobfinder.core.feed.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeedProperties.class)
class FeedConfig {
}
