package com.jobfinder.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PiiRedactorTests {

    @Test
    void masksEmailAddresses() {
        String out = PiiRedactor.redact("Sign-in failed for ada.lovelace+jobs@example.co.uk, retry from mail ada@x.io");
        assertThat(out).doesNotContain("ada.lovelace").doesNotContain("example.co.uk").doesNotContain("ada@x.io")
                .contains("[email]");
    }

    @Test
    void masksJwtsAndBearerTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl";
        assertThat(PiiRedactor.redact("token was " + jwt)).doesNotContain(jwt).contains("[jwt]");
        assertThat(PiiRedactor.redact("Authorization: Bearer abc.DEF-123_xyz")).doesNotContain("abc.DEF")
                .contains("[redacted]");
        assertThat(PiiRedactor.redact("header bearer opaque-token-value-0123")).doesNotContain("opaque-token");
    }

    @ParameterizedTest
    @ValueSource(strings = { "sk-ant-api03-AbCdEfGh1234567890", "sk_live_51HabcdefgHIJK", "sk_test_4eC39HqLyjWDarj",
            "whsec_8f3a9c1d2e4b5a6c", "pk_live_TYooMQauvdEDq54NiTphI7jx", "AKIAIOSFODNN7EXAMPLE" })
    void masksProviderKeys(String key) {
        assertThat(PiiRedactor.redact("calling provider with " + key + " now")).doesNotContain(key).contains("[key]");
    }

    @ParameterizedTest
    @ValueSource(strings = { "password=hunter2hunter2", "password: hunter2hunter2", "\"password\":\"hunter2hunter2\"",
            "api_key=hunter2hunter2", "X-Service-Token: hunter2hunter2", "refresh_token=hunter2hunter2; Path=/",
            "Set-Cookie: hunter2hunter2", "secret = 'hunter2hunter2'" })
    void masksTheValueOfSecretFieldsButKeepsTheName(String text) {
        String out = PiiRedactor.redact(text);
        assertThat(out).doesNotContain("hunter2hunter2").contains("[redacted]");
    }

    @Test
    void masksInternationalPhoneNumbers() {
        assertThat(PiiRedactor.redact("call +234 803 123 4567 today")).doesNotContain("803").contains("[phone]");
    }

    @Test
    void leavesOrdinaryLogTextAlone() {
        String text = "Source remotive: run 7d9f3c1e-4b2a-4c1d-9e8f-0a1b2c3d4e5f SUCCEEDED (fetched 120, created 4) "
                + "at 2026-10-07T06:50:12Z, user-agent Mozilla/5.0, token budget 4096";
        assertThat(PiiRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void cutsAnOverlongMessage() {
        String cv = "Experienced engineer. ".repeat(1000);
        String out = PiiRedactor.redact(cv);
        assertThat(out.length()).isLessThan(PiiRedactor.MAX_LENGTH + 50);
        assertThat(out).endsWith("[truncated]");
    }

    @Test
    void passesNullAndEmptyThrough() {
        assertThat(PiiRedactor.redact(null)).isNull();
        assertThat(PiiRedactor.redact("")).isEmpty();
    }
}
