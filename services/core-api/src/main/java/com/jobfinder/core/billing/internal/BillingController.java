package com.jobfinder.core.billing.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.billing.internal.BillingService.Catalogue;
import com.jobfinder.core.billing.internal.BillingService.CheckoutLinkView;
import com.jobfinder.core.billing.internal.BillingService.LedgerPage;
import com.jobfinder.core.billing.internal.BillingService.Me;
import com.jobfinder.core.identity.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Plans, credits and payments for the signed-in user (docs/adr/0036-plans-credits-and-payments.md). Every endpoint is
 * about the caller only: the user comes from the access token, never from the request.
 */
@RestController
@RequestMapping("/billing")
class BillingController {

    /** Exactly one of {@code plan} (a plan code) or {@code pack} (a pack id); {@code provider} is STRIPE or PAYSTACK. */
    record CheckoutRequest(@Size(max = 30) String plan, @Size(max = 60) String pack,
            @NotBlank @Size(max = 20) String provider) {
    }

    private final BillingService billing;

    BillingController(BillingService billing) {
        this.billing = billing;
    }

    /** The plans and credit packs, with the prices of the providers that are switched on. */
    @GetMapping("/plans")
    Catalogue plans() {
        CurrentUser.require();
        return billing.catalogue();
    }

    /** The caller's plan, status, period end, balance, what this period granted and used, and today's cap. */
    @GetMapping("/me")
    Me me() {
        return billing.me(CurrentUser.require().id());
    }

    /**
     * Starts a hosted checkout and returns where to send the browser. 400 {@code provider_not_offered} for a provider
     * the item has no price for, 409 {@code already_subscribed}, 429 when rate limited, 502
     * {@code payment_provider_unavailable}.
     */
    @PostMapping("/checkout")
    CheckoutLinkView checkout(@Valid @RequestBody CheckoutRequest request) {
        return billing.checkout(CurrentUser.require().id(), request.plan(), request.pack(), request.provider());
    }

    /** Stops the paid plan renewing (it runs to the end of the paid period). Idempotent. */
    @PostMapping("/subscription/cancel")
    Me cancel() {
        return billing.cancel(CurrentUser.require().id());
    }

    /** The caller's credit ledger, newest first, with an opaque cursor for the next page. */
    @GetMapping("/ledger")
    LedgerPage ledger(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return billing.ledger(CurrentUser.require().id(), cursor, limit);
    }
}
