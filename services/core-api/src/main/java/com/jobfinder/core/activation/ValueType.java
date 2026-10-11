package com.jobfinder.core.activation;

/**
 * What a digested value is. It is the HMAC's domain separator, so an email digests the same whether it was the account
 * email or found in a CV, and a phone can never collide with an email.
 */
public enum ValueType {
    EMAIL,
    PHONE,
    GOOGLE_SUB,
    FILE
}
