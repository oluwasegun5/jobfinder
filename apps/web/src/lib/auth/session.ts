import { createApiClient, type components } from "@jobfinder/api-contract";

export type AuthTokens = components["schemas"]["AuthResponse"];

type Listener = (accessToken: string | null, expiresInSeconds?: number) => void;

/**
 * The access token lives only in module memory (CLAUDE.md: nothing sensitive in web storage).
 * The refresh token is an httpOnly cookie the browser sends to /api/core/auth.
 */
let accessToken: string | null = null;
let inFlightRefresh: Promise<string | null> | null = null;
const listeners = new Set<Listener>();

/** Same-origin proxy to core-api (docs/adr/0011). Absolute so it also resolves outside a real browser. */
export const API_BASE_URL = `${typeof window === "undefined" ? "" : window.location.origin}/api/core`;

// Deliberately has no auth middleware, so a failing refresh can never trigger another refresh.
const bareClient = createApiClient({ baseUrl: API_BASE_URL });

export function getAccessToken() {
  return accessToken;
}

export function setSession(tokens: AuthTokens) {
  accessToken = tokens.accessToken ?? null;
  for (const listener of listeners) listener(accessToken, tokens.expiresIn);
}

export function clearSession() {
  accessToken = null;
  for (const listener of listeners) listener(null);
}

export function subscribeToSession(listener: Listener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one request:
 * core-api rotates refresh tokens and treats a replayed one as theft (docs/adr/0012), so two
 * parallel refreshes would log the user out.
 */
export function refreshSession(): Promise<string | null> {
  inFlightRefresh ??= (async () => {
    try {
      const { data, response } = await bareClient.POST("/auth/refresh");
      if (data?.accessToken) {
        setSession(data);
        return data.accessToken;
      }
      // Only a definitive rejection ends the session; a 5xx or network blip leaves it alone.
      if (response.status === 401 || response.status === 403) clearSession();
      return null;
    } catch {
      return null;
    } finally {
      inFlightRefresh = null;
    }
  })();
  return inFlightRefresh;
}
