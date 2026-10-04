package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Which address a per-IP limit is keyed on, behind the web proxy and without it (ASVS 11.1.4; ADR 0037). */
class ClientIpResolverTests {

    private static ClientIpResolver resolver(String... trusted) {
        return new ClientIpResolver(new WebSecurityProperties(
                new WebSecurityProperties.Cors(List.of(), false, Duration.ofMinutes(10)),
                new WebSecurityProperties.Csrf(List.of()), new WebSecurityProperties.Hsts(Duration.ofDays(365), true),
                List.of(trusted)));
    }

    private static MockHttpServletRequest request(String remote, String... forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        for (String line : forwarded) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    private final ClientIpResolver defaults = resolver("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16", "fc00::/7");

    @Test
    void behindATrustedProxyTheForwardedClientIsTheKey() {
        assertThat(defaults.resolve(request("172.18.0.5", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void anAddressTheClientInventedIsNeverReachedBecauseTheEdgeAppendsTheRealOne() {
        // client sent "6.6.6.6"; the load balancer appended the address it saw; the web proxy added nothing
        assertThat(defaults.resolve(request("172.18.0.5", "6.6.6.6, 203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void trustedProxiesInTheMiddleOfTheChainAreSkipped() {
        assertThat(defaults.resolve(request("172.18.0.5", "203.0.113.9, 10.1.2.3", "192.168.1.1")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void aPeerThatIsNotATrustedProxyCannotChooseItsOwnAddress() {
        assertThat(defaults.resolve(request("198.51.100.7", "203.0.113.9"))).isEqualTo("198.51.100.7");
    }

    @Test
    void noHeaderMeansThePeer() {
        assertThat(defaults.resolve(request("172.18.0.5"))).isEqualTo("172.18.0.5");
    }

    @Test
    void aMalformedHeaderIsIgnoredAltogether() {
        for (String bad : List.of("not-an-ip", "203.0.113.9, example.com", "999.1.1.1", "203.0.113.9:8080", "")) {
            assertThat(defaults.resolve(request("172.18.0.5", bad))).as(bad).isEqualTo("172.18.0.5");
        }
    }

    @Test
    void ipv6ClientsAreKeyedByTheirNormalisedAddress() {
        assertThat(defaults.resolve(request("172.18.0.5", "2001:DB8:0:0:0:0:0:1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(defaults.resolve(request("::1", "2001:db8::1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void anEmptyTrustListMeansTheHeaderIsNeverBelieved() {
        assertThat(resolver().resolve(request("172.18.0.5", "203.0.113.9"))).isEqualTo("172.18.0.5");
    }

    @Test
    void aBadTrustEntryStopsStartup() {
        assertThatThrownBy(() -> resolver("10.0.0.0/33")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver("proxy.internal")).isInstanceOf(IllegalStateException.class);
    }
}
