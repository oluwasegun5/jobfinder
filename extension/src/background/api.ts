import { createApiClient, type ApiClient } from "@jobfinder/api-contract";

import { CORE_API_URL } from "../shared/config";
import { clearAuth, loadAuth, saveAuth, type StoredAuth } from "./session";

/** Thrown when there is no usable session and a silent refresh did not produce one: the user must sign in again. */
export class SignedOutError extends Error {
  constructor() {
    super("signed_out");
    this.name = "SignedOutError";
  }
}

/** A response we did not expect. The message never contains a body, a URL or anything from the user's data. */
export class ApiError extends Error {
  constructor(readonly status: number) {
    super(`core-api answered ${status}`);
    this.name = "ApiError";
  }
}

/** Refresh a little before the token really expires. */
const SAFETY_MARGIN_MS = 30_000;
const DEFAULT_TTL_S = 15 * 60;

function client(token?: string): ApiClient {
  return createApiClient({
    baseUrl: CORE_API_URL,
    // Cookies are only ever sent on the three /auth calls below, which opt in explicitly.
    credentials: "omit",
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
}

function expiry(expiresInSeconds: number | undefined): number {
  return Date.now() + (expiresInSeconds ?? DEFAULT_TTL_S) * 1000 - SAFETY_MARGIN_MS;
}

/** The result of a call, whatever the status: callers decide what a 404 means. */
export interface Result<D> {
  data: D | undefined;
  status: number;
}

type Call<D> = (api: ApiClient) => Promise<{ data?: D; error?: unknown; response: Response }>;

let refreshing: Promise<StoredAuth | null> | null = null;

/**
 * Silent refresh: the refresh token is an httpOnly SameSite=Strict cookie that only the browser can send, so the call is
 * made from the service worker with `credentials: "include"`. Concurrent callers share one request (a rotating refresh
 * token must not be spent twice). Returns null, with the session cleared, when it does not work.
 */
export function refreshAccess(): Promise<StoredAuth | null> {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    try {
      const previous = await loadAuth();
      const { data, response } = await client().POST("/auth/refresh", { credentials: "include" });
      if (!response.ok || !data?.accessToken) {
        await clearAuth();
        return null;
      }
      const next: StoredAuth = {
        accessToken: data.accessToken,
        expiresAt: expiry(data.expiresIn),
        email: previous?.email,
      };
      await saveAuth(next);
      return next;
    } catch {
      await clearAuth();
      return null;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

/**
 * Calls core-api with the stored access token, refreshing it first when it has expired and once more when the server
 * answers 401. Throws SignedOutError when there is no session to be had.
 */
export async function authed<D>(call: Call<D>): Promise<Result<D>> {
  let auth = await loadAuth();
  if (!auth || auth.expiresAt <= Date.now()) {
    auth = await refreshAccess();
  }
  if (!auth) throw new SignedOutError();
  let result = await call(client(auth.accessToken));
  if (result.response.status === 401) {
    auth = await refreshAccess();
    if (!auth) throw new SignedOutError();
    result = await call(client(auth.accessToken));
  }
  if (result.response.status === 401) throw new SignedOutError();
  return { data: result.data, status: result.response.status };
}

/** Like {@link authed} but anything other than 2xx is an ApiError, except the statuses listed in `allow`. */
export async function authedOk<D>(call: Call<D>, allow: number[] = []): Promise<Result<D>> {
  const result = await authed(call);
  if ((result.status < 200 || result.status >= 300) && !allow.includes(result.status)) {
    throw new ApiError(result.status);
  }
  return result;
}

export async function login(email: string, password: string): Promise<"ok" | "rejected" | "failed"> {
  try {
    const { data, response } = await client().POST("/auth/login", {
      body: { email, password },
      credentials: "include",
    });
    if (response.status === 401 || response.status === 400 || response.status === 403) return "rejected";
    if (!response.ok || !data?.accessToken) return "failed";
    await saveAuth({ accessToken: data.accessToken, expiresAt: expiry(data.expiresIn), email: email.trim().toLowerCase() });
    return "ok";
  } catch {
    return "failed";
  }
}

/** Always clears the local session; tells the server (and clears the cookie) when it can. */
export async function logout(): Promise<void> {
  try {
    await client().POST("/auth/logout", { credentials: "include" });
  } catch {
    // Offline or the server is down: the local session is cleared below regardless.
  } finally {
    await clearAuth();
  }
}
