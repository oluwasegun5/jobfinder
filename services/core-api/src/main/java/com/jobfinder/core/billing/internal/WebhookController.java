package com.jobfinder.core.billing.internal;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.billing.internal.PaymentProvider.InvalidWebhookException;
import com.jobfinder.core.billing.internal.SubscriptionService.EventNotResolvableException;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * The provider webhooks: public (no JWT, see {@code WebhookSecurityConfig}) and authenticated by the signature over the
 * raw body alone. The body is taken as bytes so the signature is checked over exactly what was sent, before anything
 * is parsed. A bad signature is 400 with no body; a handled, duplicate or ignored event is 200; an event that needs
 * its companion to arrive first is 503, so the provider redelivers it. Not part of the public OpenAPI document.
 */
@Hidden
@RestController
@RequestMapping("/webhooks")
class WebhookController {

    private final WebhookService webhooks;

    WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(value = "/stripe", consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> stripe(@RequestBody byte[] body, @RequestHeader HttpHeaders headers) {
        return handle(Provider.STRIPE, body, headers);
    }

    @PostMapping(value = "/paystack", consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> paystack(@RequestBody byte[] body, @RequestHeader HttpHeaders headers) {
        return handle(Provider.PAYSTACK, body, headers);
    }

    private ResponseEntity<Void> handle(Provider provider, byte[] body, HttpHeaders headers) {
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
}
