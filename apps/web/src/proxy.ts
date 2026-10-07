import { NextResponse, type NextRequest } from "next/server";

import { activeTraceparent } from "@/lib/observability/active-span";
import { traceparentToForward } from "@/lib/observability/trace-context";
import {
  contentSecurityPolicy,
  isBlockedCorePath,
  SECURITY_HEADERS,
  strictTransportSecurity,
} from "@/lib/security/edge";

/**
 * Runs before routing and before the /api/core rewrite: blocks the core-api paths a browser must not reach, and gives
 * every page a per-request CSP nonce (Next reads the nonce from the request's policy and applies it to its scripts).
 */
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (isBlockedCorePath(pathname)) {
    return new NextResponse(JSON.stringify({ status: 404, title: "Not Found" }), {
      status: 404,
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  }
  if (pathname.startsWith("/api/")) {
    // The call to core-api carries the trace, so a browser request is one trace from here to ai-service (ADR 0039).
    const forwarded = new Headers(request.headers);
    forwarded.set(
      "traceparent",
      traceparentToForward(request.headers.get("traceparent"), activeTraceparent()),
    );
    return NextResponse.next({ request: { headers: forwarded } });
  }

  const nonce = btoa(crypto.randomUUID());
  const csp = contentSecurityPolicy(nonce, process.env.NODE_ENV === "development");
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", csp);
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) response.headers.set(name, value);
  const hsts = strictTransportSecurity(request.headers.get("x-forwarded-proto"), request.nextUrl.protocol);
  if (hsts) response.headers.set("Strict-Transport-Security", hsts);
  return response;
}

export const config = {
  matcher: [
    { source: "/((?!_next/static|_next/image|favicon.ico).*)" },
  ],
};
