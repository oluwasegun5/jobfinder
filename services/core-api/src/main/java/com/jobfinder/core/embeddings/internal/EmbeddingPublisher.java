package com.jobfinder.core.embeddings.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Queues one embed request: {@code {"id": "<uuid>"}} on the kind's queue (default exchange, routed straight to
 * it). The message names the row and carries nothing else: ai-service fetches the text from core-api, so no resume
 * content ever travels through the broker (as in ADR 0016).
 */
@Component
class EmbeddingPublisher {

    private final RabbitTemplate rabbit;
    private final EmbeddingProperties properties;

    EmbeddingPublisher(RabbitTemplate rabbit, EmbeddingProperties properties) {
        this.rabbit = rabbit;
        this.properties = properties;
    }

    void publish(EmbeddingKind kind, UUID id) {
        Message message = MessageBuilder.withBody(("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(UUID.randomUUID().toString())
                .build();
        rabbit.send("", properties.queue(kind), message);
    }
}
