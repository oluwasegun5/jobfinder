#!/usr/bin/env bash
# Puts the previous release (or the given tag) back. Application only: the database is not touched. That is safe because
# every migration must be backward compatible (expand, then contract in a LATER release), so the old image runs against
# the new schema. If a release broke that rule, or data is damaged, restore instead (docs/runbooks/backup-restore.md).
#   infra/deploy/rollback.sh [TAG]
set -uo pipefail
# shellcheck source=deploy-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/deploy-lib.sh"

target="${1:-$(read_state previous_tag)}"
[ -n "$target" ] || die "no previous tag recorded in $STATE_DIR; pass the tag to go back to"
valid_tag "$target" || die "'$target' is not a valid image tag"
current="$(read_state current_tag)"
[ "$target" != "$current" ] || die "$target is already the deployed tag"
[ -f "$ENV_FILE" ] || die "env file $ENV_FILE not found"

log "rolling back ${current:-?} -> $target"
bring_up "$target" || die "$target did not come up healthy; see docs/runbooks/incident.md"
run_smoke || die "$target is up but fails the smoke test"
[ -z "$current" ] || write_state previous_tag "$current"
write_state current_tag "$target"
printf '%s %s (rollback)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$target" >> "$STATE_DIR/history"
log "now running $target"
