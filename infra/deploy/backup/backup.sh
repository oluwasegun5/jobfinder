#!/usr/bin/env bash
# One backup of the application database: pg_dump custom format, verified, optionally encrypted, optionally copied
# off-host. Usage: backup.sh [daily|pre-deploy|manual]   (ADR 0041, docs/runbooks/backup-restore.md)
#
# Environment: see backup-lib.sh. BACKUP_PASSPHRASE_FILE (a file holding the passphrase) turns encryption on;
# BACKUP_S3_URI (s3://bucket/prefix) with BACKUP_S3_ENDPOINT, BACKUP_S3_ACCESS_KEY and BACKUP_S3_SECRET_KEY turns the
# off-host copy on.
set -euo pipefail
umask 077
# shellcheck source=backup-lib.sh
. "$(dirname "$0")/backup-lib.sh"

label="${1:-daily}"
case "$label" in
  daily | pre-deploy | manual) ;;
  *) die "unknown label '$label' (daily, pre-deploy or manual)" ;;
esac
require_integer BACKUP_RETENTION_DAYS "$BACKUP_RETENTION_DAYS" 1

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="$BACKUP_DIR/jobfinder-$stamp-$label"
partial="$base.dump.partial"
trap 'rm -f "$partial" "$partial.enc"' EXIT

log "dumping $PGDATABASE@$PGHOST"
pg_dump --format=custom --compress=6 --no-owner --file="$partial"

# A dump that cannot be read back is not a backup: list its table of contents and require data sections.
toc="$(pg_restore --list "$partial")" || die "the dump cannot be read back (pg_restore --list failed)"
printf '%s\n' "$toc" | grep -q 'TABLE DATA' || die "the dump has no table data; refusing to keep it"

final="$base.dump"
if [ -n "${BACKUP_PASSPHRASE_FILE:-}" ]; then
  [ -r "$BACKUP_PASSPHRASE_FILE" ] || die "BACKUP_PASSPHRASE_FILE is set but not readable"
  [ -s "$BACKUP_PASSPHRASE_FILE" ] || die "BACKUP_PASSPHRASE_FILE is empty"
  openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass "file:$BACKUP_PASSPHRASE_FILE" -in "$partial" -out "$partial.enc"
  final="$base.dump.enc"
  mv "$partial.enc" "$final"
  rm -f "$partial"
else
  mv "$partial" "$final"
  log "WARNING: BACKUP_PASSPHRASE_FILE is not set; this backup is not encrypted at the file level"
fi

(cd "$BACKUP_DIR" && sha256sum "$(basename "$final")" > "$(basename "$final").sha256")
size="$(wc -c < "$final" | tr -d ' ')"
log "kept $(basename "$final") ($size bytes)"

if [ -n "${BACKUP_S3_URI:-}" ]; then
  uri="${BACKUP_S3_URI%/}"
  if aws_s3 s3 cp --only-show-errors "$final" "$uri/$(basename "$final")" \
    && aws_s3 s3 cp --only-show-errors "$final.sha256" "$uri/$(basename "$final").sha256"; then
    touch "$BACKUP_DIR/.last_upload_ok"
    log "copied off-host to $uri/"
  else
    log "ERROR: the off-host copy failed; the local backup is kept"
    exit 3
  fi
fi
printf '%s\n' "$final"
