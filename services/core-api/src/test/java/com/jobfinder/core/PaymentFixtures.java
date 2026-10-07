package com.jobfinder.core;

/** Fake credentials for the payment tests. None of these is a real key or secret. */
public final class PaymentFixtures {

    public static final String STRIPE_KEY = "sk_test_FAKE_not_a_real_stripe_key";
    public static final String STRIPE_WEBHOOK_SECRET = "whsec_FAKE_not_a_real_signing_secret";
    public static final String PAYSTACK_KEY = "sk_test_FAKE_not_a_real_paystack_key";

    /** The prices the test catalogue charges (minor units), set through the BILLING_PRICE_* properties below. */
    public static final long PRO_USD = 1500;
    public static final long PRO_NGN = 2_500_000;
    public static final long PACK_SMALL_USD = 499;
    public static final long PACK_SMALL_NGN = 800_000;
    public static final long PACK_LARGE_USD = 1999;
    public static final long PACK_LARGE_NGN = 3_000_000;

    private PaymentFixtures() {
    }
}
