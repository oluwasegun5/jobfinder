/**
 * Edge rules for the web app (P6.2, ADR 0037): which core-api paths the browser-facing proxy may forward and the
 * Content-Security-Policy every page is served with. Pure functions so they are unit-tested without a server.
 */

/** core-api path prefixes a browser must never reach through /api/core (service-to-service and operations). */
const BLOCKED_CORE_PREFIXES = ["/internal", "/actuator"] as const;

/** The one actuator endpoint the app itself reads (the status badge); core-api serves it with no detail. */
const ALLOWED_CORE_PATHS = ["/actuator/health"] as const;

/**
 * True when the request path is /api/core/<blocked prefix>... after the normalisation a server applies before routing
 * (percent-decoding, dot segments, repeated slashes, case), so an encoded or dotted spelling cannot slip past.
 */
export function isBlockedCorePath(pathname: string): boolean {
  let decoded = pathname;
  // Decode repeatedly (double encoding) but never loop forever on hostile input.
  for (let i = 0; i < 3; i += 1) {
    try {
      const next = decodeURIComponent(decoded);
      if (next === decoded) break;
      decoded = next;
    } catch {
      return true; // malformed escapes are never legitimate
    }
  }
  const segments: string[] = [];
  for (const raw of decoded.replace(/\\/g, "/").split("/")) {
    // Path parameters (";a=b") are dropped from each segment BEFORE dot segments are resolved: a servlet container
    // strips them first, so "..;/" is ".." to it and must be to us too.
    const part = raw.split(";")[0].toLowerCase();
    if (part === "" || part === ".") continue;
    if (part === "..") {
      segments.pop();
    } else {
      segments.push(part);
    }
  }
  if (segments[0] !== "api" || segments[1] !== "core") return false;
  const rest = `/${segments.slice(2).join("/")}`;
  if ((ALLOWED_CORE_PATHS as readonly string[]).includes(rest)) return false;
  return BLOCKED_CORE_PREFIXES.some((p) => rest === p || rest.startsWith(`${p}/`));
}

/**
 * The page policy. Scripts run only with this response's nonce ('strict-dynamic' lets Next's loaded chunks run);
 * no 'unsafe-inline' or 'unsafe-eval' for scripts in production. Styles keep 'unsafe-inline' because React and the UI
 * kit set style attributes (recorded as a residual risk in docs/security-review.md).
 */
export function contentSecurityPolicy(nonce: string, development: boolean): string {
  const script = [`'self'`, `'nonce-${nonce}'`, `'strict-dynamic'`];
  if (development) script.push(`'unsafe-eval'`);
  const directives = [
    `default-src 'self'`,
    `script-src ${script.join(" ")}`,
    `style-src 'self' 'unsafe-inline'`,
    `img-src 'self' data: blob:`,
    `font-src 'self'`,
    `connect-src 'self'`,
    `media-src 'self' blob:`,
    `object-src 'none'`,
    `base-uri 'none'`,
    `form-action 'self'`,
    `frame-ancestors 'none'`,
    `worker-src 'self' blob:`,
  ];
  if (!development) directives.push("upgrade-insecure-requests");
  return directives.join("; ");
}

/**
 * HSTS only when the request reached us over TLS (the edge says so with X-Forwarded-Proto, or the URL itself is https),
 * so plain-http local development is never pinned to https.
 */
export function strictTransportSecurity(forwardedProto: string | null, protocol: string): string | null {
  const proto = (forwardedProto ?? "").split(",")[0].trim().toLowerCase();
  return proto === "https" || (proto === "" && protocol === "https:")
    ? "max-age=31536000; includeSubDomains"
    : null;
}

/** Headers added to every page response next to the CSP. */
export const SECURITY_HEADERS: Readonly<Record<string, string>> = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "X-Frame-Options": "DENY",
  "Permissions-Policy": "camera=(), microphone=(self), geolocation=(), payment=(), usb=(), interest-cohort=()",
  "Cross-Origin-Opener-Policy": "same-origin",
};
