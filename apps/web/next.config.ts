import path from "node:path";

import type { NextConfig } from "next";

const monorepoRoot = path.join(__dirname, "../..");

// Where the Next server forwards /api/core/* (read at build time; see docs/adr/0011).
const coreApiUrl = (process.env.CORE_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");

const nextConfig: NextConfig = {
  output: "standalone",
  outputFileTracingRoot: monorepoRoot,
  turbopack: { root: monorepoRoot },
  transpilePackages: ["@jobfinder/api-contract"],
  poweredByHeader: false,
  async rewrites() {
    return [{ source: "/api/core/:path*", destination: `${coreApiUrl}/:path*` }];
  },
};

export default nextConfig;
