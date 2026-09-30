import { createApiClient, type Middleware } from "@jobfinder/api-contract";

import { API_BASE_URL, getAccessToken, refreshSession } from "@/lib/auth/session";

// A clone of each outgoing request, kept so it can be replayed once after a silent refresh.
const replayable = new WeakMap<Request, Request>();

export const authMiddleware: Middleware = {
  onRequest({ request }) {
    const token = getAccessToken();
    if (!token) return request;
    const authorized = new Request(request, {
      headers: new Headers({ ...Object.fromEntries(request.headers), Authorization: `Bearer ${token}` }),
    });
    replayable.set(authorized, authorized.clone());
    return authorized;
  },
  async onResponse({ request, response }) {
    const retry = replayable.get(request);
    replayable.delete(request);
    if (response.status !== 401 || !retry) return undefined;

    const token = await refreshSession();
    if (!token) return undefined;
    retry.headers.set("Authorization", `Bearer ${token}`);
    return fetch(retry);
  },
};

/**
 * Browser-side core-api client. Requests go to the web origin under /api/core and the
 * Next server forwards them to core-api (see next.config.ts and docs/adr/0011). When the
 * access token has expired the middleware refreshes it silently and replays the request once.
 */
export const api = createApiClient({ baseUrl: API_BASE_URL });
api.use(authMiddleware);
