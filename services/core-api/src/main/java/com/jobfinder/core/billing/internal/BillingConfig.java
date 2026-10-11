package com.jobfinder.core.billing.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({BillingProperties.class, TrialProperties.class})
class BillingConfig {
}
