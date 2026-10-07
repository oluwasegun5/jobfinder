#!/usr/bin/env bash
# Shared by deploy.sh and rollback.sh. Sourced, never run.
# shellcheck shell=bash

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-jobfinder}"
STATE_DIR="${STATE_DIR:-$REPO_ROOT/.deploy-state}"
# Extra compose files after the production overlay (the local rehearsal adds its stand-ins here).
COMPOSE_EXTRA_FILES="${COMPOSE_EXTRA_FILES:-}"
# false: images are already on the host (rehearsal builds them locally).
DEPLOY_PULL="${DEPLOY_PULL:-true}"
PREFLIGHT_MODE="${PREFLIGHT_MODE:-production}"
APP_IMAGES="core-api ai-service web backup"

log() { printf '%s deploy: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

dc() {
  local files=(-f "$REPO_ROOT/infra/docker-compose.yml" -f "$REPO_ROOT/infra/docker-compose.prod.yml")
  local f
  for f in $COMPOSE_EXTRA_FILES; do files+=(-f "$f"); done
  docker compose -p "$COMPOSE_PROJECT" "${files[@]}" --env-file "$ENV_FILE" "$@"
}

valid_tag() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'; }
env_value() { grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'; }

read_state() { [ -f "$STATE_DIR/$1" ] && cat "$STATE_DIR/$1" || true; }
write_state() { mkdir -p "$STATE_DIR"; printf '%s\n' "$2" > "$STATE_DIR/$1"; }

# Starts (or updates) the stack at TAG and waits until everything is healthy.
bring_up() { # tag
  local tag="$1"
  if [ "$DEPLOY_PULL" = "true" ]; then
    log "pulling images for $tag"
    IMAGE_TAG="$tag" dc pull --quiet || return 1
  fi
  log "starting the stack at $tag (core-api applies pending Flyway migrations on start)"
  IMAGE_TAG="$tag" dc up -d --no-build --wait --wait-timeout "${DEPLOY_WAIT_SECONDS:-600}" --remove-orphans
}

# Waits until the public URL answers 200 (the proxy needs a moment to see a restarted web container). Readiness only:
# the certificate is verified by the smoke test itself.
wait_for_edge() { # base url
  local i code
  for i in $(seq 1 "${DEPLOY_EDGE_WAIT_SECONDS:-90}"); do
    code="$(curl --silent --insecure --max-time 5 --output /dev/null --write-out '%{http_code}' "$1/" 2>/dev/null || true)"
    [ "$code" = "200" ] && return 0
    sleep 1
  done
  log "the public URL $1 did not answer 200 within ${DEPLOY_EDGE_WAIT_SECONDS:-90}s (last status: ${code:-none})"
  return 1
}

run_smoke() {
  local base
  base="$(env_value WEB_BASE_URL)"
  [ -n "$base" ] || { log "WEB_BASE_URL is not set; cannot smoke test"; return 1; }
  wait_for_edge "$base" || return 1
  if [ -n "${DEPLOY_PRE_SMOKE_HOOK:-}" ]; then eval "$DEPLOY_PRE_SMOKE_HOOK" || return 1; fi
  SMOKE_BASE_URL="$base" "$REPO_ROOT/infra/deploy/smoke.sh"
}
