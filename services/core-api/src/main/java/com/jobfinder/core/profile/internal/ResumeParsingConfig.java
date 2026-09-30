package com.jobfinder.core.profile.internal;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the parse queue and its dead-letter queue (created on connect by Spring's RabbitAdmin).
 * Messages the worker cannot even read are rejected into the dead-letter queue, so a poison
 * message is parked for inspection instead of being redelivered forever.
 */
@Configuration
@EnableConfigurationProperties({ AiServiceProperties.class, ResumeParsingProperties.class })
class ResumeParsingConfig {

    @Bean
    Queue resumeParseQueue(ResumeParsingProperties properties) {
        return QueueBuilder.durable(properties.queue())
                .deadLetterExchange("")
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    @Bean
    Queue resumeParseDeadLetterQueue(ResumeParsingProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }
}
