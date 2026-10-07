#!/usr/bin/env bash
# Deploys an image tag to this host and rolls back by itself when it does not come up healthy.
#   infra/deploy/deploy.sh TAG
#
# TAG is the image tag CI published (a release tag such as v1.2.0, or sha-<commit> for staging). Steps:
#   1. preflight of the env file          4. pull images, then `up -d --wait` (core-api runs Flyway on start)
#   2. record the running tag             5. smoke test through the public URL
#   3. pre-deploy database backup         6. on failure: put the previous tag back (docs/runbooks/rollback.md)
# Settings: ENV_FILE (default <repo>/.env), COMPOSE_PROJECT (default jobfinder), STATE_DIR, SKIP_BACKUP=1 (never in
# production; for the very first deploy there is nothing to back up and it is skipped by itself), NO_ROLLBACK=1.
set -uo pipefail
# shellcheck source=deploy-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/deploy-lib.sh"

tag="${1:-}"
[ -n "$tag" ] || die "usage: deploy.sh TAG"
valid_tag "$tag" || die "'$tag' is not a valid image tag"
[ "$tag" != "latest" ] || die "deploy a specific tag, never 'latest' (it cannot be rolled back to)"
[ -f "$ENV_FILE" ] || die "env file $ENV_FILE not found"
[ "$COMPOSE_PROJECT" != "" ] || die "COMPOSE_PROJECT is empty"

log "deploying $tag to project $COMPOSE_PROJECT"
"$REPO_ROOT/infra/deploy/preflight.sh" "$ENV_FILE" $([ "$PREFLIGHT_MODE" = "rehearsal" ] && echo --rehearsal) || die "preflight failed; nothing was changed"
dc config --quiet || die "the compose files do not validate with this env file; nothing was changed"

previous="$(read_state current_tag)"
log "currently deployed: ${previous:-nothing (first deploy)}"

if [ -n "$(dc ps --status running --quiet postgres 2>/dev/null)" ]; then
  if [ "${SKIP_BACKUP:-0}" = "1" ]; then
    log "WARNING: SKIP_BACKUP=1, no pre-deploy backup"
  else
    log "taking the pre-deploy backup"
    dc exec -T backup /usr/local/bin/backup.sh pre-deploy >/dev/null || die "pre-deploy backup failed; nothing was changed"
  fi
else
  log "no running database: first deploy, nothing to back up"
fi

rollback() {
  if [ "${NO_ROLLBACK:-0}" = "1" ] || [ -z "$previous" ]; then
    log "NOT rolling back ($([ -z "$previous" ] && echo 'there is no previous tag' || echo 'NO_ROLLBACK=1')). The stack is in the state shown by: docker compose -p $COMPOSE_PROJECT ps"
    return 0
  fi
  log "ROLLING BACK to $previous"
  if bring_up "$previous" && run_smoke; then
    log "rolled back to $previous and it passes the smoke test. Migrations of $tag stay applied (they are backward compatible by rule)."
  else
    log "CRITICAL: the rollback to $previous did not come up healthy either. Follow docs/runbooks/incident.md (restore if data is damaged)."
  fi
}

if ! bring_up "$tag"; then
  log "the stack did not become healthy"
  dc ps || true
  dc logs --no-color --tail 40 core-api || true
  rollback
  exit 1
fi
if ! run_smoke; then
  log "the smoke test failed"
  rollback
  exit 1
fi

[ -z "$previous" ] || write_state previous_tag "$previous"
write_state current_tag "$tag"
printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$tag" >> "$STATE_DIR/history"
log "deployed $tag"
