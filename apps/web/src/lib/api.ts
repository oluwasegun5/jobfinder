import { createApiClient } from "@jobfinder/api-contract";

/**
 * Browser-side core-api client. Requests go to the web origin under /api/core and the
 * Next server forwards them to core-api (see next.config.ts and docs/adr/0011).
 */
export const api = createApiClient({ baseUrl: "/api/core" });
