import type { ApiClient } from "@jobfinder/api-contract";
import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api";

export type CoreApiHealth = { status: string };

// Actuator doesn't publish a response schema, so the generated type is an opaque object.
function readStatus(body: unknown): string | undefined {
  if (typeof body === "object" && body !== null && "status" in body) {
    const { status } = body as { status: unknown };
    return typeof status === "string" ? status : undefined;
  }
  return undefined;
}

/**
 * Actuator answers 200 when UP and 503 with the same body shape when DOWN, so both a
 * successful and an error response can carry a status. Anything else is a failure.
 */
export async function fetchCoreApiHealth(client: ApiClient): Promise<CoreApiHealth> {
  const { data, error, response } = await client.GET("/actuator/health");
  const status = readStatus(data ?? error);
  if (!status) {
    throw new Error(`core-api health check failed (HTTP ${response.status})`);
  }
  return { status };
}

export function useCoreApiHealth(client: ApiClient = api) {
  return useQuery({
    queryKey: ["core-api", "health"],
    queryFn: () => fetchCoreApiHealth(client),
    refetchInterval: 30_000,
  });
}
