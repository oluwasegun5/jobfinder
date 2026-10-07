package com.jobfinder.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The SSRF guard (ADR 0037): what it refuses, what it lets through, and that a changed DNS answer is re-checked. */
class SsrfGuardTests {

    private static final Map<String, String> DNS = Map.of(
            "boards.example.com", "93.184.216.34",
            "metadata.attacker.test", "169.254.169.254",
            "internal.attacker.test", "10.0.0.5",
            "loopback.attacker.test", "127.0.0.1",
            "v6.attacker.test", "fd00::1",
            "mapped.attacker.test", "::ffff:127.0.0.1");

    private final SsrfGuard strict = new SsrfGuard(SsrfGuard.Policy.STRICT, host -> {
        String address = DNS.get(host);
        if (address != null) {
            return new InetAddress[] { InetAddress.getByName(address) };
        }
        // Literal addresses (including odd spellings) resolve themselves, as the real resolver does.
        if (host.matches("[0-9a-fA-Fx.:]+")) {
            return InetAddress.getAllByName(host);
        }
        throw new UnknownHostException(host);
    });

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/", "https://localhost/", "https://[::1]/", "https://0.0.0.0/",
            "https://169.254.169.254/latest/meta-data/", "https://10.1.2.3/", "https://172.16.0.1/",
            "https://192.168.1.1/", "https://100.64.0.1/", "https://[fd00::1]/", "https://[fe80::1]/",
            "https://[::ffff:127.0.0.1]/", "https://[::ffff:169.254.169.254]/", "https://[64:ff9b::7f00:1]/",
            "https://[2002:7f00:1::]/", "https://2130706433/", "https://0x7f.1/", "https://017700000001/",
            "https://metadata.attacker.test/", "https://internal.attacker.test/", "https://loopback.attacker.test/",
            "https://v6.attacker.test/", "https://mapped.attacker.test/", "https://255.255.255.255/",
            "https://224.0.0.1/" })
    void internalAddressesAreRefusedHoweverTheyAreSpelled(String url) {
        assertThatThrownBy(() -> strict.check(URI.create(url))).isInstanceOf(SsrfGuard.BlockedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://boards.example.com/", "ftp://boards.example.com/", "file:///etc/passwd",
            "gopher://boards.example.com/", "https://user:pass@boards.example.com/", "https://boards.example.com:8443/",
            "https://boards.example.com:22/" })
    void otherSchemesUserInfoAndPortsAreRefused(String url) {
        assertThatThrownBy(() -> strict.check(URI.create(url))).isInstanceOf(SsrfGuard.BlockedException.class);
    }

    @Test
    void aPublicHttpsUrlIsAllowed() {
        assertThatCode(() -> strict.check(URI.create("https://boards.example.com/v1/boards/acme/jobs?content=true")))
                .doesNotThrowAnyException();
        assertThatCode(() -> strict.check(URI.create("https://boards.example.com:443/x"))).doesNotThrowAnyException();
    }

    @Test
    void aRelativeOrMissingUrlIsRefused() {
        assertThatThrownBy(() -> strict.check(URI.create("/just/a/path"))).isInstanceOf(SsrfGuard.BlockedException.class);
        assertThatThrownBy(() -> strict.check(null)).isInstanceOf(SsrfGuard.BlockedException.class);
    }

    @Test
    void aNameThatDoesNotResolveIsRefusedAsUnresolvableNotAsHostile() {
        assertThatThrownBy(() -> strict.check(URI.create("https://nowhere.example.invalid/")))
                .isInstanceOfSatisfying(SsrfGuard.BlockedException.class, e -> assertThat(e.unresolvable()).isTrue());
    }

    @Test
    void theMessageNeverRepeatsTheQueryOrCredentials() {
        assertThatThrownBy(() -> strict.check(URI.create("https://internal.attacker.test/x?app_key=SECRET-KEY")))
                .hasMessageNotContaining("SECRET-KEY").hasMessageNotContaining("attacker");
    }

    @Test
    void aHostWhoseDnsAnswerChangesIsCheckedAgainOnEveryRequest() {
        // DNS rebinding: public the first time, internal afterwards.
        AtomicInteger lookups = new AtomicInteger();
        SsrfGuard guard = new SsrfGuard(SsrfGuard.Policy.STRICT, host -> new InetAddress[] { InetAddress
                .getByName(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "169.254.169.254") });

        assertThatCode(() -> guard.check(URI.create("https://rebind.attacker.test/"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.check(URI.create("https://rebind.attacker.test/")))
                .isInstanceOf(SsrfGuard.BlockedException.class);
    }

    @Test
    void oneInternalAddressAmongPublicOnesIsEnoughToRefuse() {
        SsrfGuard guard = new SsrfGuard(SsrfGuard.Policy.STRICT, host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.7") });

        assertThatThrownBy(() -> guard.check(URI.create("https://mixed.attacker.test/")))
                .isInstanceOf(SsrfGuard.BlockedException.class);
    }

    @Test
    void theRelaxedPolicyUsedByTestsAllowsLocalhostOverHttp() {
        SsrfGuard relaxed = new SsrfGuard(new SsrfGuard.Policy(true, true));

        assertThatCode(() -> relaxed.check(URI.create("http://localhost:8089/x"))).doesNotThrowAnyException();
    }
}
