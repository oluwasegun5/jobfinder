package com.jobfinder.core.notifications.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationsConfig {
}
