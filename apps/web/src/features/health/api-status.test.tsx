import { createApiClient } from "@jobfinder/api-contract";
import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { renderWithQueryClient } from "@/test/render";

import { ApiStatus } from "./api-status";
import { fetchCoreApiHealth } from "./core-api-health";

function clientReturning(respond: () => Response | Promise<Response>) {
  const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => respond());
  return { fetch, client: createApiClient({ baseUrl: "http://core-api.test", fetch }) };
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("fetchCoreApiHealth", () => {
  it("calls the actuator health endpoint through the generated client", async () => {
    const { fetch, client } = clientReturning(() => json({ status: "UP" }));

    await expect(fetchCoreApiHealth(client)).resolves.toEqual({ status: "UP" });
    expect(fetch).toHaveBeenCalledOnce();
    expect(fetch.mock.calls[0][0].url).toBe("http://core-api.test/actuator/health");
  });

  it("reads the status from a 503 DOWN response", async () => {
    const { client } = clientReturning(() => json({ status: "DOWN" }, 503));

    await expect(fetchCoreApiHealth(client)).resolves.toEqual({ status: "DOWN" });
  });

  it("fails when the response carries no status", async () => {
    const { client } = clientReturning(() => new Response("Bad Gateway", { status: 502 }));

    await expect(fetchCoreApiHealth(client)).rejects.toThrow("HTTP 502");
  });
});

describe("ApiStatus", () => {
  it("shows operational when core-api is UP", async () => {
    const { client } = clientReturning(() => json({ status: "UP" }));
    renderWithQueryClient(<ApiStatus client={client} />);

    expect(screen.getByRole("status")).toHaveTextContent("Checking…");
    expect(await screen.findByText("Operational")).toBeInTheDocument();
  });

  it("shows degraded when core-api reports DOWN", async () => {
    const { client } = clientReturning(() => json({ status: "DOWN" }, 503));
    renderWithQueryClient(<ApiStatus client={client} />);

    expect(await screen.findByText("Degraded (DOWN)")).toBeInTheDocument();
  });

  it("shows unreachable when the request fails", async () => {
    const { client } = clientReturning(() => Promise.reject(new TypeError("Failed to fetch")));
    renderWithQueryClient(<ApiStatus client={client} />);

    expect(await screen.findByText("Unreachable")).toBeInTheDocument();
  });
});
