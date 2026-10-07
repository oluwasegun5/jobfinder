package com.jobfinder.core.billing;

import java.math.BigDecimal;
import java.net.URI;

import org.springframework.http.HttpStatus;

/**
 * The user's credit balance is zero or less. HTTP 402 with the stable code {@code insufficient_credits}, the problem
 * type {@link #TYPE} and the {@code balance} member; the web client recognises it and offers an upgrade or a top-up.
 * Restored by a plan grant or a top-up, not by waiting for the daily reset.
 */
public class InsufficientCreditsException extends AiAllowanceException {

    public static final String CODE = "insufficient_credits";
    public static final URI TYPE = URI.create("urn:jobfinder:problem:insufficient-credits");

    private final BigDecimal balance;

    public InsufficientCreditsException(BigDecimal balance) {
        super(HttpStatus.PAYMENT_REQUIRED, CODE,
                "You have no AI credits left. Upgrade your plan or buy a credit pack to continue.");
        this.balance = balance;
        withType(TYPE);
        withProperty("balance", balance.stripTrailingZeros().toPlainString());
    }

    public BigDecimal balance() {
        return balance;
    }
}
