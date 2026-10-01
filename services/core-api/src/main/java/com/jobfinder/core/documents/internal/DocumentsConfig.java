package com.jobfinder.core.documents.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ DocumentsProperties.class, DocumentsAiProperties.class })
class DocumentsConfig {
}
