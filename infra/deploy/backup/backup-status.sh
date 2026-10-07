#!/usr/bin/env bash
# Exit 0 when a daily backup newer than BACKUP_MAX_AGE_HOURS exists (and, with an off-host copy configured, that copy
# succeeded within the same window). Used as the container health check and by docs/runbooks/backup-restore.md.
set -euo pipefail
# shellcheck source=backup-lib.sh
. "$(dirname "$0")/backup-lib.sh"
require_integer BACKUP_MAX_AGE_HOURS "$BACKUP_MAX_AGE_HOURS" 1
minutes=$((BACKUP_MAX_AGE_HOURS * 60))

fresh="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'jobfinder-*-daily.dump*' ! -name '*.sha256' -mmin "-$minutes" 2>/dev/null | head -n 1)"
if [ -z "$fresh" ]; then
  log "no daily backup in the last $BACKUP_MAX_AGE_HOURS hours"
  exit 1
fi
if [ -n "${BACKUP_S3_URI:-}" ] && [ -z "$(find "$BACKUP_DIR" -maxdepth 1 -name '.last_upload_ok' -mmin "-$minutes" 2>/dev/null)" ]; then
  log "no successful off-host copy in the last $BACKUP_MAX_AGE_HOURS hours"
  exit 1
fi
echo "ok: $(basename "$fresh")"
