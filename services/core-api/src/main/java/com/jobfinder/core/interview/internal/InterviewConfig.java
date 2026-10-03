package com.jobfinder.core.interview.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ InterviewProperties.class, InterviewAiProperties.class, MockInterviewProperties.class })
class InterviewConfig {
}
