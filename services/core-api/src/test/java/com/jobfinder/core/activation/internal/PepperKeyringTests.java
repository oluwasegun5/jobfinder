package com.jobfinder.core.activation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.jobfinder.core.activation.DigestSet;
import com.jobfinder.core.activation.IdentityDigest;
import com.jobfinder.core.activation.ValueType;

/** HMAC-SHA-256 under a keyring of peppers (docs/adr/0042-trial-credit-abuse-gate.md, section 3). */
class PepperKeyringTests {

    private static final String P1 = "test-pepper-one-0123456789abcdef0123456789";
    private static final String P2 = "test-pepper-two-0123456789abcdef0123456789";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static PepperKeyring ring(short current, Map<Short, byte[]> peppers) {
        return new PepperKeyring(peppers, current);
    }

    @Test
    void theDigestIsHmacSha256OfTypeSeparatorAndValue() throws Exception {
        PepperKeyring ring = ring((short) 1, Map.of((short) 1, bytes(P1)));

        DigestSet set = ring.digest(ValueType.EMAIL, "abc@gmail.com");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bytes(P1), "HmacSHA256"));
        byte[] expected = mac.doFinal(bytes("EMAIL\u001Fabc@gmail.com"));
        assertThat(set.type()).isEqualTo(ValueType.EMAIL);
        assertThat(set.current().keyVersion()).isEqualTo((short) 1);
        assertThat(set.current().value()).isEqualTo(expected).hasSize(32);
    }

    @Test
    void theValueTypeSeparatesDomainsButNotTheKindOfClaim() {
        PepperKeyring ring = ring((short) 1, Map.of((short) 1, bytes(P1)));

        assertThat(ring.digest(ValueType.EMAIL, "x").current().value())
                .isNotEqualTo(ring.digest(ValueType.PHONE, "x").current().value());
        assertThat(ring.digest(ValueType.EMAIL, "x").current().value())
                .isEqualTo(ring.digest(ValueType.EMAIL, "x").current().value());
    }

    @Test
    void everyLiveVersionIsDigestedWithTheCurrentOneFirst() {
        PepperKeyring ring = ring((short) 2, Map.of((short) 1, bytes(P1), (short) 2, bytes(P2)));

        DigestSet set = ring.digest(ValueType.PHONE, "+2348031234567");

        assertThat(set.digests()).extracting(IdentityDigest::keyVersion).containsExactly((short) 2, (short) 1);
        assertThat(set.digests().get(0).value()).isNotEqualTo(set.digests().get(1).value());
        assertThat(ring.currentVersion()).isEqualTo((short) 2);
    }

    @Test
    void theCurrentVersionMustHaveAPepperAndPeppersMustBeLongEnough() {
        assertThatThrownBy(() -> ring((short) 2, Map.of((short) 1, bytes(P1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TRIAL_PEPPER_V2");
        assertThatThrownBy(() -> ring((short) 1, Map.of((short) 1, bytes("short"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32");
    }

    @Test
    void peppersAreReadFromTheEnvironmentByVersion() {
        MockEnvironment env = new MockEnvironment().withProperty("TRIAL_PEPPER_V1", P1)
                .withProperty("TRIAL_PEPPER_V2", P2).withProperty("TRIAL_PEPPER_VX", "ignored");

        PepperKeyring ring = PepperKeyring.fromEnvironment(env, (short) 1);

        assertThat(ring.digest(ValueType.FILE, "ab").digests()).hasSize(2);
        assertThat(ring.digest(ValueType.FILE, "ab").current().value())
                .isEqualTo(ring((short) 1, Map.of((short) 1, bytes(P1))).digest(ValueType.FILE, "ab").current().value());
    }

    @Test
    void underProdAMissingPepperStopsTheApplication() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> PepperKeyring.fromEnvironment(env, (short) 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TRIAL_PEPPER_V1");
    }

    @Test
    void outsideProdAMissingPepperFallsBackToARandomOneForThisProcess() {
        PepperKeyring a = PepperKeyring.fromEnvironment(new MockEnvironment(), (short) 1);
        PepperKeyring b = PepperKeyring.fromEnvironment(new MockEnvironment(), (short) 1);

        assertThat(a.digest(ValueType.EMAIL, "x").current().value())
                .isNotEqualTo(b.digest(ValueType.EMAIL, "x").current().value());
    }
}
