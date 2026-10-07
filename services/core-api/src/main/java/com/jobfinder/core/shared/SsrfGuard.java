package com.jobfinder.core.shared;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The one check every server-side fetch of a URL passes before it connects (ADR 0037, ASVS 12.6.1 and 5.2.6).
 *
 * <p>A URL is allowed only when it is https (http only when the policy says so, for tests and local WireMock), has a
 * host and no user info, uses the default port, and every address its host resolves to is a public one: loopback,
 * link-local (including the 169.254.169.254 cloud metadata address), private, carrier-grade NAT, unique-local,
 * multicast, unspecified and reserved ranges are refused, as are IPv4 addresses hidden inside IPv6 (mapped, NAT64,
 * 6to4). Numeric host spellings such as {@code 2130706433} or {@code 0x7f.1} are caught because the host is always
 * resolved, never string-matched.
 *
 * <p>The callers do not follow redirects (the JDK client's default), so a redirect cannot lead past this check. The
 * check runs before every request, so a host whose DNS answer changes between requests is checked again each time. A
 * name that answers differently between this check and the connect inside the same request is not closed by the JDK
 * client (it cannot pin an address and keep TLS verification); that residual is recorded in docs/security-review.md.
 */
public class SsrfGuard {

    /** Looks a host up; replaced in tests to simulate a DNS answer that changes. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** What is allowed. {@link #STRICT} is the production default; the relaxed form exists for tests only. */
    public record Policy(boolean allowHttp, boolean allowPrivateAddresses) {
        public static final Policy STRICT = new Policy(false, false);
    }

    /** The URL is not allowed. {@link #unresolvable()} marks a name that did not resolve (a transient condition). */
    public static final class BlockedException extends RuntimeException {

        private final boolean unresolvable;

        BlockedException(String reason, boolean unresolvable) {
            super(reason);
            this.unresolvable = unresolvable;
        }

        public boolean unresolvable() {
            return unresolvable;
        }
    }

    private final Policy policy;
    private final Resolver resolver;

    public SsrfGuard(Policy policy) {
        this(policy, InetAddress::getAllByName);
    }

    public SsrfGuard(Policy policy, Resolver resolver) {
        this.policy = policy;
        this.resolver = resolver;
    }

    /** Throws {@link BlockedException} unless {@code uri} may be fetched. Messages never contain the query or user info. */
    public void check(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            throw blocked("not an absolute URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        if (!https && !(policy.allowHttp() && scheme.equals("http"))) {
            throw blocked("scheme not allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw blocked("user info in URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw blocked("no host");
        }
        if (!policy.allowPrivateAddresses()) {
            int port = uri.getPort();
            if (port != -1 && port != (https ? 443 : 80)) {
                throw blocked("port not allowed");
            }
            for (InetAddress address : resolve(host)) {
                if (isInternal(address)) {
                    throw blocked("host resolves to a non-public address");
                }
            }
        }
    }

    private InetAddress[] resolve(String host) {
        // Strip the brackets of an IPv6 literal.
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            InetAddress[] addresses = resolver.resolve(name);
            if (addresses == null || addresses.length == 0) {
                throw new BlockedException("host did not resolve", true);
            }
            return addresses;
        } catch (UnknownHostException e) {
            throw new BlockedException("host did not resolve", true);
        }
    }

    private static BlockedException blocked(String reason) {
        return new BlockedException(reason, false);
    }

    /** True for every address that is not a routable public unicast address. */
    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return internalV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) { // fc00::/7 unique local
                return true;
            }
            if (first == 0xfe && (b[1] & 0xc0) == 0x80) { // fe80::/10
                return true;
            }
            if (first == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8) { // 2001:db8::/32 docs
                return true;
            }
            if (first == 0x20 && b[1] == 0x02) { // 6to4 2002::/16 embeds an IPv4 address in bytes 2-5
                return embeddedIsInternal(b, 2);
            }
            if (first == 0x00 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b) { // 64:ff9b::/96 NAT64
                return embeddedIsInternal(b, 12);
            }
            boolean mapped = true; // ::ffff:a.b.c.d and the deprecated ::a.b.c.d
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
                return embeddedIsInternal(b, 12);
            }
        }
        return false;
    }

    private static boolean embeddedIsInternal(byte[] b, int offset) {
        try {
            return isInternal(InetAddress.getByAddress(new byte[] { b[offset], b[offset + 1], b[offset + 2],
                    b[offset + 3] }));
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean internalV4(int a, int b, int c) {
        return a == 0 // "this" network
                || a == 10 || a == 127
                || (a == 100 && b >= 64 && b <= 127) // carrier-grade NAT
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 0 && c == 0) // IETF protocol assignments
                || (a == 192 && b == 0 && c == 2) // TEST-NET-1
                || (a == 192 && b == 168)
                || (a == 198 && (b == 18 || b == 19)) // benchmarking
                || (a == 198 && b == 51 && c == 100) // TEST-NET-2
                || (a == 203 && b == 0 && c == 113) // TEST-NET-3
                || a >= 224; // multicast and reserved (incl. 255.255.255.255)
    }
}
