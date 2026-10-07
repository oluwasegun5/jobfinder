package com.jobfinder.core.billing.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.billing.internal.PaymentProvider.InvalidWebhookException;
import com.jobfinder.core.billing.internal.SubscriptionService.EventNotResolvableException;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The provider webhooks: public (no JWT, see {@code WebhookSecurityConfig}) and authenticated by the signature over the
 * raw body alone. The body is taken as bytes so the signature is checked over exactly what was sent, before anything
 * is parsed. A bad signature is 400 with no body; a handled, duplicate or ignored event is 200; an event that needs
 * its companion to arrive first is 503, so the provider redelivers it.
 *
 * <p><b>Size.</b> A body over {@link #MAX_BODY_BYTES} (1 MB; the providers' events are a few KB) is refused with 413
 * before anything is read or verified: by its Content-Length when it states one, and otherwise by capping the read at
 * the limit plus one byte, so a streamed body cannot be buffered without bound. {@code /webhooks/**} is also rate
 * limited per client IP (the {@code WEBHOOK} endpoint class). Not part of the public OpenAPI document.
 */
@Hidden
@RestController
@RequestMapping("/webhooks")
class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /** The largest webhook body accepted, in bytes. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final WebhookService webhooks;

    WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(value = "/stripe", consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> stripe(HttpServletRequest request, @RequestHeader HttpHeaders headers) {
        return handle(Provider.STRIPE, request, headers);
    }

    @PostMapping(value = "/paystack", consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> paystack(HttpServletRequest request, @RequestHeader HttpHeaders headers) {
        return handle(Provider.PAYSTACK, request, headers);
    }

    private ResponseEntity<Void> handle(Provider provider, HttpServletRequest request, HttpHeaders headers) {
        byte[] body = readBounded(request);
        if (body == null) {
            log.warn("Webhook body too large, refused: provider={}", provider.slug());
            return ResponseEntity.status(413).build();
        }
        Map<String, String> lowercased = new HashMap<>();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                lowercased.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
            }
        });
        try {
            webhooks.handle(provider, body, lowercased);
            return ResponseEntity.ok().build();
        } catch (InvalidWebhookException e) {
            return ResponseEntity.badRequest().build();
        } catch (EventNotResolvableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** The whole body, or null when it is larger than {@link #MAX_BODY_BYTES}; never reads more than the limit + 1. */
    static byte[] readBounded(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
