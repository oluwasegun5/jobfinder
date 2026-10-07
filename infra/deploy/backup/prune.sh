#!/usr/bin/env bash
# Deletes backups older than BACKUP_RETENTION_DAYS, locally and (with BACKUP_S3_URI) off-host. Strictly by age: the
# retention period is a promise to users (their deleted data leaves the backups within it), so there is no "keep the
# newest N" exception; backup-status.sh is what notices when backups stop being made.
set -euo pipefail
# shellcheck source=backup-lib.sh
. "$(dirname "$0")/backup-lib.sh"

require_integer BACKUP_RETENTION_DAYS "$BACKUP_RETENTION_DAYS" 1
# Test knob: age limit in minutes instead of days (the round-trip test uses 0 to prune what it just made).
minutes="${PRUNE_AGE_MINUTES_OVERRIDE:-$((BACKUP_RETENTION_DAYS * 1440))}"
require_integer PRUNE_AGE_MINUTES_OVERRIDE "$minutes" 0

[ -d "$BACKUP_DIR" ] || exit 0
deleted="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'jobfinder-*.dump*' -mmin "+$minutes" -print -delete | wc -l | tr -d ' ')"
log "pruned $deleted local file(s) older than $BACKUP_RETENTION_DAYS day(s)"

if [ -n "${BACKUP_S3_URI:-}" ] && [ "${BACKUP_S3_PRUNE:-true}" = "true" ]; then
  uri="${BACKUP_S3_URI%/}"
  rest="${uri#s3://}"
  bucket="${rest%%/*}"
  prefix=""
  [ "$rest" = "$bucket" ] || prefix="${rest#*/}/"
  cutoff="$(date -u -d "now - $minutes minutes" +%Y-%m-%dT%H:%M:%S)"
  keys="$(aws_s3 s3api list-objects-v2 --bucket "$bucket" --prefix "${prefix}jobfinder-" \
    --query "Contents[?LastModified<='${cutoff}'].Key" --output text 2>/dev/null || true)"
  count=0
  for key in $keys; do
    [ "$key" = "None" ] && continue
    case "$key" in *jobfinder-*.dump*) ;; *) continue ;; esac
    if aws_s3 s3api delete-object --bucket "$bucket" --key "$key" >/dev/null; then
      count=$((count + 1))
    else
      log "WARNING: could not delete $key from the off-host store (check its lifecycle rule too)"
    fi
  done
  log "pruned $count off-host object(s) older than $BACKUP_RETENTION_DAYS day(s)"
fi
