#!/usr/bin/env bash
# Rehearses the production deployment locally, in a throw-away compose project (ADR 0041). Touches nothing outside
# Docker project $REHEARSAL_PROJECT (default jobfinder-p65), its volumes and images, and $REHEARSAL_DIR (secrets).
# Never contacts a cloud, registry, domain or real provider.
#
#   rehearsal.sh up            generate secrets, build images, deploy through deploy.sh, smoke test
#   rehearsal.sh smoke         smoke test through Caddy (local CA)
#   rehearsal.sh ports         only Caddy publishes ports; data stores are not reachable from the host
#   rehearsal.sh backup        automated backup -> restore round trip into a scratch database
#   rehearsal.sh restore-drill disaster drill: lose data, restore the backup into the live database, verify
#   rehearsal.sh rollback      deploy a broken tag, expect an automatic rollback to the good one
#   rehearsal.sh load          k6 load test through Caddy (modest by default; see infra/deploy/loadtest/)
#   rehearsal.sh down          remove containers, volumes, images and the generated secrets
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cd "$repo"

PROJECT="${REHEARSAL_PROJECT:-jobfinder-p65}"
[ "$PROJECT" != "jobfinder" ] || { echo "refusing to use the default 'jobfinder' project" >&2; exit 1; }
DIR="${REHEARSAL_DIR:-${TMPDIR:-/tmp}/$PROJECT}"
ENV_FILE="$DIR/rehearsal.env"
HTTPS_PORT="${REHEARSAL_HTTPS_PORT:-18443}"
HTTP_PORT="${REHEARSAL_HTTP_PORT:-18080}"
MAIL_PORT_LOCAL="${REHEARSAL_MAIL_PORT:-18025}"
REHEARSAL_OVERLAY="$here/docker-compose.rehearsal.yml"

say() { printf '\n== %s\n' "$*"; }
rand() { openssl rand -hex "${1:-24}"; }
dc() {
  docker compose -p "$PROJECT" -f "$repo/infra/docker-compose.yml" -f "$repo/infra/docker-compose.prod.yml" \
    -f "$REHEARSAL_OVERLAY" --env-file "$ENV_FILE" "$@"
}
deploy() { # tag
  ENV_FILE="$ENV_FILE" COMPOSE_PROJECT="$PROJECT" COMPOSE_EXTRA_FILES="$REHEARSAL_OVERLAY" DEPLOY_PULL=false \
    PREFLIGHT_MODE=rehearsal STATE_DIR="$DIR/state" DEPLOY_PRE_SMOKE_HOOK="$here/rehearsal.sh export-ca" \
    SMOKE_CA_CERT="$DIR/caddy-root.crt" SMOKE_CERT_MIN_SECONDS=3600 SMOKE_HTTP_URL="http://localhost:$HTTP_PORT" SMOKE_MAIL_API="http://127.0.0.1:$MAIL_PORT_LOCAL" \
    "$here/deploy.sh" "$1"
}

make_env() {
  mkdir -p "$DIR/state"; chmod 700 "$DIR"
  [ ! -f "$ENV_FILE" ] || return 0
  umask 077
  {
    echo "COMPOSE_PROJECT_NAME=$PROJECT"
    echo "POSTGRES_PASSWORD=$(rand)"; echo "RABBITMQ_PASSWORD=$(rand)"; echo "REDIS_PASSWORD=$(rand)"
    echo "JWT_SECRET=$(rand 32)"; echo "AI_SERVICE_TOKEN=$(rand 32)"; echo "NOTIFICATIONS_UNSUBSCRIBE_SECRET=$(rand 32)"
    echo "OBJECT_STORAGE_ACCESS_KEY=$(rand 8)"; echo "OBJECT_STORAGE_SECRET_KEY=$(rand 16)"
    echo "OBJECT_STORAGE_BUCKET=jobfinder"
    echo "OBJECT_STORAGE_ENDPOINT=http://object-storage:9090"; echo "OBJECT_STORAGE_PATH_STYLE=true"; echo "OBJECT_STORAGE_REGION=us-east-1"
    echo "MAIL_HOST=mailpit"; echo "MAIL_PORT=1025"; echo "SMTP_AUTH=false"; echo "SMTP_STARTTLS=false"
    echo "ACME_EMAIL="; echo "SITE_ADDRESS=localhost"; echo "CADDY_TLS=tls internal"; echo "PUBLIC_BIND=127.0.0.1"
    echo "HTTP_PORT=$HTTP_PORT"; echo "HTTPS_PORT=$HTTPS_PORT"; echo "MAILPIT_UI_PORT=$MAIL_PORT_LOCAL"
    echo "WEB_BASE_URL=https://localhost:$HTTPS_PORT"
    echo "IMAGE_REGISTRY=$PROJECT"
    echo "UPLOAD_SCANNER_TYPE=none"
    echo "LLM_PROVIDER=fake"; echo "EMBEDDING_PROVIDER=fake"
    echo "MATCHING_BATCH_ENABLED=false"; echo "NOTIFICATIONS_DIGEST_ENABLED=false"; echo "NOTIFICATIONS_INSTANT_ENABLED=false"
    echo "BILLING_JOBS_ENABLED=false"; echo "APPLICATION_REMINDERS_ENABLED=false"
    echo "SENTRY_ENVIRONMENT=rehearsal"
    echo "BACKUP_RETENTION_DAYS=30"
    echo "BACKUP_S3_URI=s3://jobfinder/backups"; echo "BACKUP_S3_ENDPOINT=http://object-storage:9090"
    echo "BACKUP_S3_REGION=us-east-1"
  } > "$ENV_FILE"
  # The off-host copy reuses the mock store's (random) credentials; it also gets an encryption passphrase file.
  ak="$(grep '^OBJECT_STORAGE_ACCESS_KEY=' "$ENV_FILE" | cut -d= -f2-)"; sk="$(grep '^OBJECT_STORAGE_SECRET_KEY=' "$ENV_FILE" | cut -d= -f2-)"
  { echo "BACKUP_S3_ACCESS_KEY=$ak"; echo "BACKUP_S3_SECRET_KEY=$sk"; } >> "$ENV_FILE"
  rand 32 > "$DIR/backup-passphrase"; chmod 444 "$DIR/backup-passphrase"
  { echo "BACKUP_PASSPHRASE_HOST_FILE=$DIR/backup-passphrase"; echo "BACKUP_PASSPHRASE_FILE=/run/secrets/backup_passphrase"; } >> "$ENV_FILE"
  echo "generated $ENV_FILE"
}

smoke() {
  export_ca
  SMOKE_BASE_URL="https://localhost:$HTTPS_PORT" SMOKE_HTTP_URL="http://localhost:$HTTP_PORT" SMOKE_CA_CERT="$DIR/caddy-root.crt" \
    SMOKE_MAIL_API="http://127.0.0.1:$MAIL_PORT_LOCAL" SMOKE_CERT_MIN_SECONDS=3600 SMOKE_REQUIRE_AUTHED=1 "$@" "$here/smoke.sh"
}
export_ca() { dc cp caddy:/data/caddy/pki/authorities/local/root.crt "$DIR/caddy-root.crt" >/dev/null 2>&1; test -s "$DIR/caddy-root.crt"; }

cmd="${1:-help}"
case "$cmd" in
  env) make_env ;;
  export-ca) export_ca ;;
  up)
    make_env
    say "building images (tag local)"
    IMAGE_TAG=local dc build core-api ai-service web backup
    say "deploying through deploy.sh"
    deploy local
    ;;
  smoke) smoke ;;
  ports)
    say "published ports of the stack"
    published="$(dc ps --format '{{.Service}} {{.Ports}}' | grep -e '->' || true)"
    echo "$published"
    bad="$(echo "$published" | grep -vE '^(caddy|mailpit) ' | grep . || true)"
    [ -z "$bad" ] || { echo "UNEXPECTED published ports: $bad" >&2; exit 1; }
    echo "$published" | grep -q '^caddy ' || { echo "caddy publishes nothing" >&2; exit 1; }
    for svc in postgres redis rabbitmq core-api ai-service web; do
      echo "$published" | grep -q "^$svc " && { echo "$svc is published" >&2; exit 1; }
    done
    echo "OK: only caddy (and the rehearsal-only Mailpit API on localhost) publish ports"
    ;;
  backup)
    say "backup -> restore round trip"
    dc exec -T backup /usr/local/bin/roundtrip-test.sh
    say "off-host copy, status and pruning"
    dc exec -T backup /usr/local/bin/backup.sh manual >/dev/null
    dc exec -T backup /usr/local/bin/backup-status.sh || true
    dc exec -T backup sh -c 'ls -l /backups; aws --version >/dev/null && AWS_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" AWS_DEFAULT_REGION=us-east-1 aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3 ls "$BACKUP_S3_URI/"'
    dc exec -T -e PRUNE_AGE_MINUTES_OVERRIDE=0 backup /usr/local/bin/prune.sh
    dc exec -T backup sh -c 'test -z "$(ls /backups | grep -v "^\\." )" && echo "local backups pruned"; AWS_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" AWS_DEFAULT_REGION=us-east-1 aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3 ls "$BACKUP_S3_URI/" | wc -l'
    ;;
  restore-drill)
    say "creating a user to protect"
    smoke env SMOKE_KEEP_USER=1 SMOKE_CREDENTIALS_FILE="$DIR/drill-user.txt" >/dev/null
    email="$(sed -n 1p "$DIR/drill-user.txt")"; password="$(sed -n 2p "$DIR/drill-user.txt")"
    psql() { dc exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\${PSQL_DB:-\$POSTGRES_DB}\" -tA -c \"$1\""; }
    fp="select count(*) || ' users, ids md5 ' || md5(coalesce(string_agg(id::text, ',' order by id), '')) from users"
    before="$(psql "$fp")"; echo "before: $before"
    say "taking the backup"
    file="$(dc exec -T backup /usr/local/bin/backup.sh manual | tail -n 1 | tr -d '\r')"; echo "backup: $file"
    say "DISASTER: the user is lost"
    psql "delete from users where email = '$email'" >/dev/null
    echo "after the loss: $(psql "$fp")"
    say "stopping the application (nothing may hold the live database) and restoring"
    dc stop web core-api >/dev/null
    dc exec -T backup /usr/local/bin/restore.sh "$file" --replace-live
    say "restarting"
    dc up -d --no-build --wait core-api web >/dev/null
    after="$(psql "$fp")"; echo "after the restore: $after"
    [ "$before" = "$after" ] || { echo "RESTORE DRILL FAILED: users differ" >&2; exit 1; }
    export_ca
    code="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$DIR/caddy-root.crt" -H 'Content-Type: application/json' -H "Origin: https://localhost:$HTTPS_PORT" \
      -d "{\"email\":\"$email\",\"password\":\"$password\"}" "https://localhost:$HTTPS_PORT/api/core/auth/login")"
    [ "$code" = "200" ] || { echo "RESTORE DRILL FAILED: the restored user cannot log in (HTTP $code)" >&2; exit 1; }
    echo "the restored user logs in (HTTP 200)"
    old="$(dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d postgres -tA -c "select datname from pg_database where datname like \$\$%_before_restore_%\$\$"' | tr -d '\r')"
    echo "dropping the pre-restore database: $old"
    dc exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d postgres -c 'DROP DATABASE \"$old\"'"
    echo "RESTORE DRILL OK"
    ;;
  rollback)
    say "deploying a tag that cannot start; expecting an automatic rollback to local"
    if deploy no-such-tag; then echo "expected the deploy to fail" >&2; exit 1; fi
    say "state after the failed deploy"
    dc ps --format '{{.Service}} {{.Status}}'
    cur="$(cat "$DIR/state/current_tag")"; [ "$cur" = "local" ] || { echo "current tag is $cur, expected local" >&2; exit 1; }
    smoke >/dev/null && echo "the stack is serving at the previous tag after the failed deploy: ROLLBACK OK"
    ;;
  load)
    shift
    "$here/loadtest/run.sh" "$PROJECT" "$@"
    ;;
  down)
    dc down -v --remove-orphans || true
    for i in core-api ai-service web backup; do docker rmi -f "$PROJECT/$i:local" >/dev/null 2>&1 || true; done
    rm -rf "$DIR"
    echo "removed project $PROJECT"
    ;;
  *) sed -n 2,16p "$0"; exit 64 ;;
esac
