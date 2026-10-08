#!/usr/bin/env bash
# Restores a backup made by backup.sh. Usage:
#   restore.sh FILE --target NAME [--drop-existing]   restore into a NEW database NAME (rehearsals, inspection)
#   restore.sh FILE --replace-live                    restore and swap it in as the live database
#
# --replace-live is the disaster path. Stop core-api first (nothing else may be connected to the live database); the
# script refuses otherwise. The previous live database is NOT dropped: it is renamed <db>_before_restore_<time> so the
# swap can be undone, and must be dropped by hand once the restore is confirmed (it still holds the data the
# retention period promises to remove). docs/runbooks/backup-restore.md has the whole procedure.
#
# FILE may be a .dump or a .dump.enc (decrypted with BACKUP_PASSPHRASE_FILE). The .sha256 next to it, when present, is
# verified first.
set -euo pipefail
umask 077
# shellcheck source=backup-lib.sh
. "$(dirname "$0")/backup-lib.sh"

file="${1:-}"
[ -n "$file" ] && [ -f "$file" ] || die "usage: restore.sh FILE (--target NAME [--drop-existing] | --replace-live)"
shift
target=""
replace_live=false
drop_existing=false
while [ $# -gt 0 ]; do
  case "$1" in
    --target) target="${2:-}"; shift 2 ;;
    --replace-live) replace_live=true; shift ;;
    --drop-existing) drop_existing=true; shift ;;
    *) die "unknown argument $1" ;;
  esac
done
if $replace_live; then
  [ -z "$target" ] || die "--replace-live and --target are exclusive"
  target="${PGDATABASE}_restoring"
  drop_existing=true
fi
[ -n "$target" ] || die "give --target NAME or --replace-live"
case "$target" in *[!a-zA-Z0-9_]* | '') die "database names are limited to letters, digits and underscore" ;; esac
[ "$target" != "$PGDATABASE" ] || die "refusing to restore over the live database '$PGDATABASE'; use --replace-live"

psql_admin() { psql --no-psqlrc -v ON_ERROR_STOP=1 -X -q -d postgres "$@"; }

sum="$file.sha256"
if [ -f "$sum" ]; then
  (cd "$(dirname "$file")" && sha256sum --check --status "$(basename "$sum")") || die "checksum mismatch: the backup file is damaged"
  log "checksum ok"
else
  log "WARNING: no .sha256 next to the file; integrity not checked"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dump="$file"
case "$file" in
  *.enc)
    [ -n "${BACKUP_PASSPHRASE_FILE:-}" ] && [ -r "$BACKUP_PASSPHRASE_FILE" ] || die "the backup is encrypted: set BACKUP_PASSPHRASE_FILE"
    dump="$work/restore.dump"
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -pass "file:$BACKUP_PASSPHRASE_FILE" -in "$file" -out "$dump" \
      || die "decryption failed (wrong passphrase or damaged file)"
    ;;
esac
pg_restore --list "$dump" >/dev/null || die "not a readable pg_dump custom-format file"

exists="$(psql_admin -tA -c "SELECT 1 FROM pg_database WHERE datname = '$target'")"
if [ -n "$exists" ]; then
  $drop_existing || die "database '$target' exists; pass --drop-existing to replace it"
  psql_admin -c "DROP DATABASE \"$target\" WITH (FORCE)"
fi
psql_admin -c "CREATE DATABASE \"$target\""

log "restoring into $target"
# One transaction: a failure leaves an empty database, never a half-restored one. --no-owner: objects belong to the
# connecting role, which is the application's own.
pg_restore --exit-on-error --single-transaction --no-owner --dbname="$target" "$dump"
tables="$(psql --no-psqlrc -X -tA -d "$target" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
log "restored $tables public table(s) into $target"

if $replace_live; then
  others="$(psql_admin -tA -c "SELECT count(*) FROM pg_stat_activity WHERE datname = '$PGDATABASE' AND pid <> pg_backend_pid()")"
  if [ "$others" != "0" ]; then
    psql_admin -c "DROP DATABASE \"$target\" WITH (FORCE)"
    die "$others connection(s) still open on $PGDATABASE: stop core-api (and anything else using it) first. The restored copy was discarded; nothing changed."
  fi
  old="${PGDATABASE}_before_restore_$(date -u +%Y%m%dt%H%M%S)"
  psql_admin -c "ALTER DATABASE \"$PGDATABASE\" RENAME TO \"$old\"" -c "ALTER DATABASE \"$target\" RENAME TO \"$PGDATABASE\""
  log "swapped in. The previous database is kept as $old; drop it after confirming: DROP DATABASE \"$old\";"
fi
