import { NextResponse, type NextRequest } from "next/server";

import { contentSecurityPolicy, isBlockedCorePath, SECURITY_HEADERS } from "@/lib/security/edge";

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
  if (pathname.startsWith("/api/")) return NextResponse.next();

  const nonce = btoa(crypto.randomUUID());
  const csp = contentSecurityPolicy(nonce, process.env.NODE_ENV === "development");
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", csp);
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) response.headers.set(name, value);
  return response;
}

export const config = {
  matcher: [
    { source: "/((?!_next/static|_next/image|favicon.ico).*)" },
  ],
};
