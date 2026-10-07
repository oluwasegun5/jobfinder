#!/usr/bin/env bash
# Automated backup -> restore round trip (run inside the backup container, against the running database).
#   1. optionally writes a probe table (rows plus a pgvector column) so the check is meaningful on an empty schema
#   2. fingerprints every public table (row count and a hash of its content) before and after taking a backup
#   3. restores the backup into a scratch database and fingerprints that
#   4. requires identical fingerprints for every table that did not change while the backup ran; a table that did
#      change (a live system keeps writing) must still hold a row count between its before and after counts
#   5. drops the scratch database and the probe table, and prunes the test backup
# Exit 0 only when everything matched. Usage: roundtrip-test.sh [--no-probe]
set -euo pipefail
here="$(dirname "$0")"
# shellcheck source=backup-lib.sh
. "$here/backup-lib.sh"

probe=true
[ "${1:-}" = "--no-probe" ] && probe=false
scratch="${PGDATABASE}_roundtrip_check"
work="$(mktemp -d)"
cleanup() {
  psql --no-psqlrc -X -q -d postgres -c "DROP DATABASE IF EXISTS \"$scratch\" WITH (FORCE)" >/dev/null 2>&1 || true
  if $probe; then
    psql --no-psqlrc -X -q -d "$PGDATABASE" -c 'DROP TABLE IF EXISTS public.zz_backup_probe' >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

# table<TAB>rows<TAB>content hash, one line per public base table.
fingerprint() { # database
  psql --no-psqlrc -X -tA -F $'\t' -d "$1" <<'SQL'
SELECT table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text,
       (xpath('/row/h/text()', query_to_xml(format('select md5(coalesce(string_agg(t::text, ''|'' order by t::text), '''')) as h from %I.%I t', table_schema, table_name), false, true, '')))[1]::text
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;
SQL
}

if $probe; then
  psql --no-psqlrc -X -q -v ON_ERROR_STOP=1 -d "$PGDATABASE" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
DROP TABLE IF EXISTS public.zz_backup_probe;
CREATE TABLE public.zz_backup_probe (id int PRIMARY KEY, note text NOT NULL, embedding vector(3) NOT NULL, created timestamptz NOT NULL DEFAULT now());
INSERT INTO public.zz_backup_probe (id, note, embedding)
SELECT g, 'probe row ' || g || ' with unicode: Ọlá ✓', ('[' || g || ',' || (g * 2) || ',' || (g * 3) || ']')::vector
FROM generate_series(1, 250) g;
SQL
fi

fingerprint "$PGDATABASE" > "$work/before.tsv"
# No off-host copy: a test file does not belong in the real bucket.
file="$(BACKUP_S3_URI="" "$here/backup.sh" manual | tail -n 1)"
fingerprint "$PGDATABASE" > "$work/after.tsv"

"$here/restore.sh" "$file" --target "$scratch" --drop-existing
fingerprint "$scratch" > "$work/restored.tsv"

status=0
total=0
stable=0
volatile=0
while IFS=$'\t' read -r table rows hash; do
  total=$((total + 1))
  before_line="$(grep -P "^${table}\t" "$work/before.tsv" || true)"
  restored_line="$(grep -P "^${table}\t" "$work/restored.tsv" || true)"
  before_rows="$(printf '%s' "$before_line" | cut -f2)"
  before_hash="$(printf '%s' "$before_line" | cut -f3)"
  restored_rows="$(printf '%s' "$restored_line" | cut -f2)"
  restored_hash="$(printf '%s' "$restored_line" | cut -f3)"
  if [ -z "$restored_line" ]; then
    log "MISMATCH $table: missing from the restored database"; status=1; continue
  fi
  if [ "$before_hash" = "$hash" ]; then
    stable=$((stable + 1))
    if [ "$restored_hash" != "$hash" ] || [ "$restored_rows" != "$rows" ]; then
      log "MISMATCH $table: live $rows rows ($hash), restored $restored_rows rows ($restored_hash)"; status=1
    fi
  else
    volatile=$((volatile + 1))
    lo=$before_rows; hi=$rows
    [ "$lo" -le "$hi" ] || { lo=$rows; hi=$before_rows; }
    if [ "$restored_rows" -lt "$lo" ] || [ "$restored_rows" -gt "$hi" ]; then
      log "MISMATCH $table: changed during the backup ($before_rows -> $rows rows) but the restore has $restored_rows"; status=1
    fi
  fi
done < "$work/after.tsv"

[ "$(wc -l < "$work/restored.tsv")" -eq "$total" ] || { log "MISMATCH: the restored database has a different number of tables ($(wc -l < "$work/restored.tsv") vs $total)"; status=1; }
if $probe; then
  grep -qP '^zz_backup_probe\t250\t' "$work/restored.tsv" || { log "MISMATCH: probe table did not come back with its 250 rows"; status=1; }
  restored_vec="$(psql --no-psqlrc -X -tA -d "$scratch" -c "SELECT embedding::text FROM zz_backup_probe WHERE id = 7")"
  [ "$restored_vec" = "[7,14,21]" ] || { log "MISMATCH: pgvector value came back as '$restored_vec'"; status=1; }
fi

# The test backup is not worth keeping (and the probe is gone from the live database in cleanup).
rm -f "$file" "$file.sha256"
[ "$status" -eq 0 ] || die "round trip FAILED"
echo "ROUNDTRIP OK: $total table(s) compared, $stable identical (rows and content hash), $volatile changed during the backup and within range"
if $probe; then echo "ROUNDTRIP OK: probe table (250 rows, pgvector) restored exactly"; fi
