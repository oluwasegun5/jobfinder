#!/usr/bin/env bash
# Backup -> restore round trip against a throw-away Postgres/pgvector container, no stack needed (runs in CI and locally).
# Creates its own Docker network, database container and backup image, and removes them again.
#   - plain and encrypted backups restore to identical table contents (rows and content hashes, pgvector column included)
#   - a damaged backup file is refused by the checksum, and a wrong passphrase by decryption
#   - the restore into a live database refuses while another connection is open, and swaps when none is
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
name="jf-roundtrip-$$"
net="$name-net"
image="$name-backup"
pg="$name-pg"
password="$(openssl rand -hex 16)"
work="$(mktemp -d)"
cleanup() {
  docker rm -f "$pg" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  docker rmi -f "$image" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

pg_image="$(grep -o 'pgvector/pgvector:pg16@sha256:[0-9a-f]*' "$repo/infra/docker-compose.yml" | head -n 1)"
[ -n "$pg_image" ] || { echo "cannot find the pinned Postgres image in docker-compose.yml" >&2; exit 1; }

docker network create "$net" >/dev/null
docker run -d --name "$pg" --network "$net" -e POSTGRES_USER=jobfinder -e POSTGRES_DB=jobfinder -e POSTGRES_PASSWORD="$password" "$pg_image" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$pg" pg_isready -h 127.0.0.1 -U jobfinder -d jobfinder >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$pg" pg_isready -h 127.0.0.1 -U jobfinder -d jobfinder >/dev/null

# A small schema that looks like the application's: relations, text with unicode, jsonb, timestamps, a vector column.
docker exec -i "$pg" psql -U jobfinder -d jobfinder -v ON_ERROR_STOP=1 -q <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE users (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), email text UNIQUE NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE jobs (id bigserial PRIMARY KEY, title text NOT NULL, detail jsonb NOT NULL, embedding vector(4) NOT NULL);
CREATE TABLE applications (id bigserial PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE, job_id bigint NOT NULL REFERENCES jobs(id), note text);
INSERT INTO users (email) SELECT 'user' || g || '@example.test' FROM generate_series(1, 40) g;
INSERT INTO jobs (title, detail, embedding) SELECT 'Ingénieur ' || g, jsonb_build_object('n', g, 'tags', jsonb_build_array('a', 'b')), ('[' || g || ',1,2,3]')::vector FROM generate_series(1, 300) g;
INSERT INTO applications (user_id, job_id, note) SELECT (SELECT id FROM users ORDER BY email LIMIT 1 OFFSET g % 40), g, 'note ✓ ' || g FROM generate_series(1, 120) g;
CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, description text);
INSERT INTO flyway_schema_history VALUES (1, 'init');
SQL

docker build -q -f "$repo/infra/docker/backup.Dockerfile" -t "$image" "$repo/infra/deploy/backup" >/dev/null

printf '%s' "$(openssl rand -hex 32)" > "$work/pass"
printf '%s' "$(openssl rand -hex 32)" > "$work/wrongpass"
chmod 444 "$work/pass" "$work/wrongpass"

backup_run() { # extra docker args..., then the image and the command
  docker run --rm --network "$net" --tmpfs /backups:uid=999,mode=0700 --tmpfs /tmp \
    -e PGHOST="$pg" -e PGUSER=jobfinder -e PGDATABASE=jobfinder -e PGPASSWORD="$password" -e BACKUP_RETENTION_DAYS=30 "$@"
}

echo "== plain backup round trip"
backup_run "$image" /usr/local/bin/roundtrip-test.sh
echo "== encrypted backup round trip"
backup_run -v "$work/pass:/run/secrets/p:ro" -e BACKUP_PASSPHRASE_FILE=/run/secrets/p "$image" /usr/local/bin/roundtrip-test.sh

echo "== damaged and wrongly-keyed backups are refused; live replacement needs the application stopped"
cat > "$work/checks.sh" <<'CHECKS'
set -u
f="$(/usr/local/bin/backup.sh manual | tail -n 1)"
if BACKUP_PASSPHRASE_FILE=/run/secrets/w /usr/local/bin/restore.sh "$f" --target wrongkey_check 2>/dev/null; then echo "FAIL: wrong passphrase accepted"; exit 1; fi
echo "ok: wrong passphrase refused"
cp "$f" /tmp/damaged.dump.enc
sed "s#  .*#  damaged.dump.enc#" "$f.sha256" > /tmp/damaged.dump.enc.sha256
printf "x" | dd of=/tmp/damaged.dump.enc bs=1 seek=100 conv=notrunc 2>/dev/null
if /usr/local/bin/restore.sh /tmp/damaged.dump.enc --target damaged_check 2>/dev/null; then echo "FAIL: damaged backup accepted"; exit 1; fi
echo "ok: damaged backup refused by the checksum"
if /usr/local/bin/restore.sh "$f" --target jobfinder 2>/dev/null; then echo "FAIL: restore over the live database accepted"; exit 1; fi
echo "ok: restoring over the live database by name is refused"
psql -X -q -d jobfinder -c "select pg_sleep(30)" >/dev/null 2>&1 &
holder=$!
sleep 2
if /usr/local/bin/restore.sh "$f" --replace-live 2>/dev/null; then echo "FAIL: replaced a database in use"; exit 1; fi
echo "ok: replacing a database that is in use is refused, nothing changed"
psql -X -q -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = 'jobfinder' and pid <> pg_backend_pid()" >/dev/null
wait "$holder" 2>/dev/null
psql -X -q -d jobfinder -tA -c "select count(*) from users" | grep -qx 40 || { echo "FAIL: live data changed"; exit 1; }
/usr/local/bin/restore.sh "$f" --replace-live
psql -X -q -d jobfinder -tA -c "select count(*) from users" | grep -qx 40 || { echo "FAIL: swapped-in data is wrong"; exit 1; }
psql -X -q -d postgres -tA -c "select datname from pg_database where datname like 'jobfinder_before_restore_%'" | grep -q . || { echo "FAIL: the old database was not kept"; exit 1; }
echo "ok: swap done, previous database kept for the operator to drop"
CHECKS
chmod 644 "$work/checks.sh"
backup_run -v "$work/pass:/run/secrets/p:ro" -v "$work/wrongpass:/run/secrets/w:ro" -v "$work/checks.sh:/opt/checks.sh:ro" \
  -e BACKUP_PASSPHRASE_FILE=/run/secrets/p "$image" bash /opt/checks.sh
echo "ROUNDTRIP CI OK"
