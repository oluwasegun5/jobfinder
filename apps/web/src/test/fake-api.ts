import { createApiClient } from "@jobfinder/api-contract";
import { vi } from "vitest";

type Route = (request: Request) => Response | Promise<Response>;

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

export type RecordedCall = { method: string; path: string; body: unknown };

/**
 * A real generated client over a stubbed `fetch`: routes are keyed "METHOD /path". A route may be replaced between
 * steps of a test, and every request (with its JSON body) is recorded. Unrouted requests answer 404.
 */
export function fakeApi(routes: Record<string, Route>) {
  const calls: RecordedCall[] = [];
  const fetch = vi.fn(async (request: Request) => {
    const path = new URL(request.url).pathname;
    const text = await request.clone().text();
    let body: unknown = text;
    try {
      body = text === "" ? undefined : JSON.parse(text);
    } catch {
      // not JSON (multipart): keep the raw text
    }
    calls.push({ method: request.method, path, body });
    const route = routes[`${request.method} ${path}`];
    return route ? route(request) : new Response(`no route for ${request.method} ${path}`, { status: 404 });
  });
  const client = createApiClient({ baseUrl: "http://core-api.test", fetch });
  return {
    client,
    calls,
    routes,
    callsTo: (method: string, path: string) => calls.filter((c) => c.method === method && c.path === path),
  };
}
