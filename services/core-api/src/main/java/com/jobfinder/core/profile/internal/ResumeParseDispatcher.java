package com.jobfinder.core.profile.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues CV parsing once an upload has committed, so a worker can never see a resume that does
 * not exist yet. If the broker cannot be reached the resume is marked FAILED straight away (the
 * user sees why and can upload again) rather than staying PENDING forever.
 */
@Component
class ResumeParseDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ResumeParseDispatcher.class);

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final ResumeParsingProperties properties;
    private final ResumeParseStore store;

    ResumeParseDispatcher(RabbitTemplate rabbit, JsonMapper json, ResumeParsingProperties properties,
            ResumeParseStore store) {
        this.rabbit = rabbit;
        this.json = json;
        this.properties = properties;
        this.store = store;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ResumeUploaded event) {
        try {
            publish(new ResumeParseMessage(event.resumeId(), event.userId(), event.versionNumber()));
        } catch (RuntimeException e) {
            log.error("Could not queue parsing of resume {}", event.resumeId(), e);
            store.fail(event.resumeId(), ParseFailureReason.QUEUE_UNAVAILABLE);
        }
    }

    /** Sends to the default exchange, routed straight to the parse queue. */
    void publish(ResumeParseMessage message) {
        Message amqp = MessageBuilder.withBody(toJson(message).getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(UUID.randomUUID().toString())
                .build();
        rabbit.send("", properties.queue(), amqp);
    }

    private String toJson(ResumeParseMessage message) {
        try {
            return json.writeValueAsString(message);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise a resume parse message", e);
        }
    }
}
