import { vi } from "vitest";

/** A just-enough chrome.storage and a routed fetch, to test the service worker's logic without a browser. */
export interface FakeStorage {
  session: Record<string, unknown>;
  local: Record<string, unknown>;
  accessLevel: string | undefined;
}

export function installFakeChrome(): FakeStorage {
  const state: FakeStorage = { session: {}, local: {}, accessLevel: undefined };
  const area = (store: Record<string, unknown>, onLevel?: (level: string) => void) => ({
    get: vi.fn(async (key: string) => (key in store ? { [key]: store[key] } : {})),
    set: vi.fn(async (items: Record<string, unknown>) => void Object.assign(store, items)),
    remove: vi.fn(async (key: string) => void delete store[key]),
    setAccessLevel: vi.fn(async (options: { accessLevel: string }) => onLevel?.(options.accessLevel)),
  });
  vi.stubGlobal("chrome", {
    storage: {
      session: area(state.session, (level) => (state.accessLevel = level)),
      local: area(state.local),
    },
    runtime: { id: "test-extension" },
  });
  return state;
}

export interface Seen {
  method: string;
  url: URL;
  authorization: string | null;
  credentials: string;
  body: unknown;
}

type Handler = (seen: Seen) => Response | Promise<Response>;

export function json(status: number, body?: unknown, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

/** Routes `METHOD /path` to handlers; anything unrouted fails the test loudly. */
export function installFetch(routes: Record<string, Handler>): Seen[] {
  const calls: Seen[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: Request | string, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const url = new URL(request.url);
      const text = request.method === "GET" || request.method === "HEAD" ? "" : await request.clone().text();
      const seen: Seen = {
        method: request.method,
        url,
        authorization: request.headers.get("authorization"),
        credentials: request.credentials,
        body: text ? (JSON.parse(text) as unknown) : undefined,
      };
      calls.push(seen);
      const handler = routes[`${request.method} ${url.pathname}`] ?? routes[`${request.method} ${url.origin}${url.pathname}`];
      if (!handler) throw new Error(`Unrouted request: ${request.method} ${url.pathname}`);
      return handler(seen);
    }),
  );
  return calls;
}
