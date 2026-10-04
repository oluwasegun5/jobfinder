package com.jobfinder.core;

/** Fake credentials for the payment tests. None of these is a real key or secret. */
public final class PaymentFixtures {

    public static final String STRIPE_KEY = "sk_test_FAKE_not_a_real_stripe_key";
    public static final String STRIPE_WEBHOOK_SECRET = "whsec_FAKE_not_a_real_signing_secret";
    public static final String PAYSTACK_KEY = "sk_test_FAKE_not_a_real_paystack_key";

    private PaymentFixtures() {
    }
}
