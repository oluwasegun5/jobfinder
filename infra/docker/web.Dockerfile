# syntax=docker/dockerfile:1
# Base images are pinned by tag AND digest (ADR 0041). Bump both together; a scheduled workflow (base-images.yml)
# reports when a newer digest exists for the tag.
# Built from the repo root so the npm workspace (apps/web + packages/api-contract) resolves.

FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build
WORKDIR /repo
ENV NEXT_TELEMETRY_DISABLED=1

COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/
COPY packages/api-contract/package.json packages/api-contract/
RUN npm ci

COPY packages/api-contract packages/api-contract
COPY apps/web apps/web
# next.config.ts is serialized at build time, so the /api/core rewrite target is baked in here.
ARG CORE_API_URL=http://core-api:8080
# NEXT_PUBLIC_* values are inlined into the browser bundle at build time. Blank hides the Google button.
ARG NEXT_PUBLIC_GOOGLE_CLIENT_ID=
RUN CORE_API_URL=$CORE_API_URL NEXT_PUBLIC_GOOGLE_CLIENT_ID=$NEXT_PUBLIC_GOOGLE_CLIENT_ID npm run build -w web

FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# outputFileTracingRoot is the repo root, so the standalone tree mirrors it (apps/web/server.js).
COPY --from=build --chown=node:node /repo/apps/web/.next/standalone ./
COPY --from=build --chown=node:node /repo/apps/web/.next/static ./apps/web/.next/static
COPY --from=build --chown=node:node /repo/apps/web/public ./apps/web/public
USER node

EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=10 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]
CMD ["node", "apps/web/server.js"]
