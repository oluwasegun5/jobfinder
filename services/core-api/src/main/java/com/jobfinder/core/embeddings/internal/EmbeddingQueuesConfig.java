package com.jobfinder.core.embeddings.internal;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the two embed queues and their dead-letter queues (created on connect by Spring's RabbitAdmin).
 * ai-service consumes them and declares them with the same arguments (RabbitMQ rejects a redeclaration with
 * different ones), so the dead-letter routing here and in ai-service's worker must stay in step.
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
class EmbeddingQueuesConfig {

    @Bean
    Queue jobsEmbedQueue(EmbeddingProperties p) {
        return QueueBuilder.durable(p.jobsQueue()).deadLetterExchange("").deadLetterRoutingKey(p.jobsDeadLetterQueue())
                .build();
    }

    @Bean
    Queue jobsEmbedDeadLetterQueue(EmbeddingProperties p) {
        return QueueBuilder.durable(p.jobsDeadLetterQueue()).build();
    }

    @Bean
    Queue resumesEmbedQueue(EmbeddingProperties p) {
        return QueueBuilder.durable(p.resumesQueue()).deadLetterExchange("")
                .deadLetterRoutingKey(p.resumesDeadLetterQueue()).build();
    }

    @Bean
    Queue resumesEmbedDeadLetterQueue(EmbeddingProperties p) {
        return QueueBuilder.durable(p.resumesDeadLetterQueue()).build();
    }
}
