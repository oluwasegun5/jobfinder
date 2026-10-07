#!/usr/bin/env bash
# The backup container's main process: a catch-up backup at start when none is fresh, then one every day at
# BACKUP_TIME_UTC, pruning after each. A failed run is retried after BACKUP_RETRY_SECONDS. BACKUP_SCHEDULER_ONCE=1 runs
# one cycle and exits (tests).
set -uo pipefail
here="$(dirname "$0")"
# shellcheck source=backup-lib.sh
. "$here/backup-lib.sh"
: "${BACKUP_RETRY_SECONDS:=900}"

recent_daily() { [ -n "$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'jobfinder-*-daily.dump*' ! -name '*.sha256' -mmin -1440 2>/dev/null | head -n 1)" ]; }

seconds_until_next() {
  local now target
  now="$(date -u +%s)"
  target="$(date -u -d "today $BACKUP_TIME_UTC" +%s)" || return 1
  [ "$target" -gt "$now" ] || target="$(date -u -d "tomorrow $BACKUP_TIME_UTC" +%s)"
  echo $((target - now))
}

cycle() {
  local rc=0
  "$here/backup.sh" daily >/dev/null || rc=$?
  "$here/prune.sh" || log "WARNING: pruning failed"
  return "$rc"
}

seconds_until_next >/dev/null || die "BACKUP_TIME_UTC must be HH:MM, got '$BACKUP_TIME_UTC'"
log "scheduler started: daily at $BACKUP_TIME_UTC UTC, keeping $BACKUP_RETENTION_DAYS day(s)"
if ! recent_daily; then
  log "no backup in the last 24 hours; taking one now"
  until cycle; do
    [ "${BACKUP_SCHEDULER_ONCE:-0}" = "1" ] && exit 1
    log "backup failed; retrying in ${BACKUP_RETRY_SECONDS}s"
    sleep "$BACKUP_RETRY_SECONDS"
  done
fi
[ "${BACKUP_SCHEDULER_ONCE:-0}" = "1" ] && exit 0

while true; do
  wait_s="$(seconds_until_next)"
  log "next backup in ${wait_s}s"
  sleep "$wait_s"
  until cycle; do
    log "backup failed; retrying in ${BACKUP_RETRY_SECONDS}s"
    sleep "$BACKUP_RETRY_SECONDS"
  done
done
