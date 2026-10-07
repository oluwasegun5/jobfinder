package com.jobfinder.core.identity.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address a per-IP rate limit is keyed on (ASVS 11.1.4, 2.2.1; docs/adr/0037-security-hardening.md).
 *
 * <p>The browser reaches core-api through the Next server, so the TCP peer is that server for every user: keying on it
 * would give all visitors one shared login and signup budget (one attacker could lock everyone out, and the per-IP
 * brute-force limit would protect nothing). The real client is in {@code X-Forwarded-For}, but that header is client
 * controlled, so it is read only when the TCP peer is itself a trusted proxy ({@code app.security.trusted-proxies}),
 * and then from the right: the first address that is not a trusted proxy is the client. A spoofed entry the client
 * added sits to the left of the one the edge appended and is never reached. A malformed header is ignored altogether.
 * Names are never resolved: only literal addresses are accepted.
 *
 * <p>This assumes the edge in front of the web app appends the address it saw (any load balancer does). If the web
 * port is exposed directly, a client can choose its own address; the production overlay therefore expects a proxy in
 * front (recorded in docs/security-review.md).
 */
@Component
class ClientIpResolver {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final List<Cidr> trusted;

    ClientIpResolver(WebSecurityProperties properties) {
        this.trusted = properties.trustedProxies().stream().filter(c -> !c.isBlank()).map(Cidr::parse).toList();
    }

    String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        InetAddress peer = literal(remote);
        if (peer == null || !isTrusted(peer)) {
            return remote;
        }
        List<String> chain = new ArrayList<>();
        Enumeration<String> lines = request.getHeaders("X-Forwarded-For");
        while (lines != null && lines.hasMoreElements()) {
            for (String part : lines.nextElement().split(",")) {
                chain.add(part.trim());
            }
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            InetAddress candidate = literal(chain.get(i));
            if (candidate == null) {
                return remote; // malformed: trust nothing in this header
            }
            if (!isTrusted(candidate)) {
                return candidate.getHostAddress();
            }
        }
        return remote;
    }

    private boolean isTrusted(InetAddress address) {
        return trusted.stream().anyMatch(cidr -> cidr.contains(address));
    }

    /** The address for a literal IPv4 or IPv6 text, or null; never touches DNS. */
    private static InetAddress literal(String text) {
        if (text == null || !(IPV4.matcher(text).matches() || text.contains(":") && IPV6.matcher(text).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            String[] parts = text.trim().split("/");
            InetAddress base = literal(parts[0]);
            if (base == null) {
                throw new IllegalStateException("app.security.trusted-proxies entry is not an address or CIDR: " + text);
            }
            int bits = base.getAddress().length * 8;
            int prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > bits) {
                throw new IllegalStateException("app.security.trusted-proxies prefix out of range: " + text);
            }
            return new Cidr(base.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xff << (8 - rest) & 0xff;
            return (candidate[full] & mask) == (network[full] & mask);
        }
    }
}
