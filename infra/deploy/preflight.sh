#!/usr/bin/env bash
# Refuses a deployment whose configuration is unsafe or incomplete, before anything is started.
#   preflight.sh ENV_FILE [--rehearsal]
# --rehearsal relaxes only what a local, throw-away run cannot satisfy (a public name, real providers).
# Exit 1 when any error is found; warnings do not fail it.
set -uo pipefail

env_file="${1:-}"
mode="${2:-production}"
[ -n "$env_file" ] && [ -f "$env_file" ] || { echo "usage: preflight.sh ENV_FILE [--rehearsal]" >&2; exit 64; }
rehearsal=false
[ "$mode" = "--rehearsal" ] && rehearsal=true

errors=0
warnings=0
err()  { errors=$((errors + 1)); printf '  ERROR   %s\n' "$*"; }
warn() { warnings=$((warnings + 1)); printf '  WARNING %s\n' "$*"; }

# Last assignment of KEY in the env file (compose env-file syntax: KEY=value, optional quotes, # comments).
val() {
  local line
  line="$(grep -E "^[[:space:]]*$1=" "$env_file" | tail -n 1)" || true
  line="${line#*=}"
  line="${line%\"}"; line="${line#\"}"; line="${line%\'}"; line="${line#\'}"
  printf '%s' "$line"
}
is_placeholder() { case "$1" in '' | change-me* | CHANGE_ME* | *PLACEHOLDER* | *example.com* | replace-me* | '<'*) return 0 ;; *) return 1 ;; esac; }
need() { # KEY [min length]
  local v; v="$(val "$1")"
  if [ -z "$v" ]; then err "$1 is not set"; return; fi
  if is_placeholder "$v"; then err "$1 still has a placeholder value"; return; fi
  if [ -n "${2:-}" ] && [ "${#v}" -lt "$2" ]; then err "$1 must be at least $2 characters"; fi
}

echo "Preflight ($mode): $env_file"

# 1. The file holds secrets: not readable by other users.
perm="$(stat -c %a "$env_file" 2>/dev/null || stat -f %Lp "$env_file" 2>/dev/null || echo 600)"
[ "${perm: -1}" = "0" ] || err "$env_file is accessible to other users (mode $perm); chmod 600"

# 2. Names and URLs.
need SITE_ADDRESS
site="$(val SITE_ADDRESS)"
web="$(val WEB_BASE_URL)"
if ! $rehearsal; then
  case "$site" in localhost* | 127.* | *:*) err "SITE_ADDRESS must be a public host name without a port (got '$site')" ;; esac
  [ "$web" = "https://$site" ] || err "WEB_BASE_URL must be exactly https://$site (got '$web')"
  [ -z "$(val CADDY_TLS)" ] || err "CADDY_TLS must be empty in production (it is only for the local rehearsal)"
  need IMAGE_REGISTRY
fi

# 3. Secrets: present, long, not the template's placeholder.
need POSTGRES_PASSWORD 24
need RABBITMQ_PASSWORD 24
need JWT_SECRET 32
need AI_SERVICE_TOKEN 32
need NOTIFICATIONS_UNSUBSCRIBE_SECRET 32
need OBJECT_STORAGE_ACCESS_KEY
need OBJECT_STORAGE_SECRET_KEY
need OBJECT_STORAGE_ENDPOINT
need OBJECT_STORAGE_BUCKET
need MAIL_HOST
need MAIL_PORT
redis_pw="$(val REDIS_PASSWORD)"
if [ "${#redis_pw}" -lt 32 ] || ! printf '%s' "$redis_pw" | grep -Eq '^[A-Za-z0-9]+$'; then
  err "REDIS_PASSWORD must be 32+ letters and digits only (it is embedded in a URL): openssl rand -hex 24"
fi

# 4. Behaviour that must not reach production.
if ! $rehearsal; then
  [ "$(val LLM_PROVIDER)" != "fake" ] || err "LLM_PROVIDER=fake is for local runs only"
  [ "$(val LLM_PROVIDER)" != "anthropic" ] || [ -n "$(val ANTHROPIC_API_KEY)" ] || err "LLM_PROVIDER=anthropic needs ANTHROPIC_API_KEY"
  [ "$(val EMBEDDING_PROVIDER)" != "fake" ] || warn "EMBEDDING_PROVIDER=fake: matching quality needs real embeddings (EMBEDDING_PROVIDER=voyage and VOYAGE_API_KEY)"
  if [ "$(val UPLOAD_SCANNER_TYPE)" = "none" ] && [ "$(val ALLOW_NO_UPLOAD_SCAN)" != "true" ]; then
    err "UPLOAD_SCANNER_TYPE=none disables virus scanning of CVs; leave it unset (ClamAV) or accept the risk with ALLOW_NO_UPLOAD_SCAN=true"
  fi
  [ "$(val UPLOAD_SCANNER_ON_ERROR)" != "open" ] || warn "UPLOAD_SCANNER_ON_ERROR=open accepts uploads when the scanner is down"
  [ "$(val AUTH_COOKIE_SECURE)" != "false" ] || err "AUTH_COOKIE_SECURE=false is not allowed in production"
  [ -z "$(val ADMIN_PASSWORD)" ] || warn "ADMIN_PASSWORD is set: remove it from the env file after the first start (the account exists then)"
  # Billing: a provider with a key needs real prices and ids (core-api would refuse to start anyway; say it early).
  if [ -n "$(val STRIPE_SECRET_KEY)" ]; then
    need STRIPE_WEBHOOK_SECRET; need STRIPE_PRICE_PRO_USD
    for k in BILLING_PRICE_PRO_USD_MINOR BILLING_PRICE_PACK_SMALL_USD_MINOR BILLING_PRICE_PACK_LARGE_USD_MINOR; do
      [ "$(val "$k")" != "100" ] && [ -n "$(val "$k")" ] || err "$k is unset or still the placeholder 100"
    done
  fi
  if [ -n "$(val PAYSTACK_SECRET_KEY)" ]; then
    need PAYSTACK_PLAN_PRO_NGN
    for k in BILLING_PRICE_PRO_NGN_MINOR BILLING_PRICE_PACK_SMALL_NGN_MINOR BILLING_PRICE_PACK_LARGE_NGN_MINOR; do
      [ "$(val "$k")" != "100" ] && [ -n "$(val "$k")" ] || err "$k is unset or still the placeholder 100"
    done
  fi
  if [ -z "$(val STRIPE_SECRET_KEY)" ] && [ -z "$(val PAYSTACK_SECRET_KEY)" ]; then
    warn "no payment provider key is set: nobody can subscribe (fine for a staging environment)"
  fi
  [ -n "$(val CORE_API_SENTRY_DSN)" ] || warn "no Sentry DSN: errors are only in the container logs"
fi

# 5. Backups.
days="$(val BACKUP_RETENTION_DAYS)"
if [ -n "$days" ]; then
  case "$days" in *[!0-9]* | 0) err "BACKUP_RETENTION_DAYS must be a whole number of days, at least 1" ;; esac
fi
if [ -n "$(val BACKUP_S3_URI)" ]; then
  need BACKUP_S3_ENDPOINT; need BACKUP_S3_ACCESS_KEY; need BACKUP_S3_SECRET_KEY
  case "$(val BACKUP_S3_ADDRESSING_STYLE)" in
    '' | path | virtual | auto) ;;
    *) err "BACKUP_S3_ADDRESSING_STYLE must be path, virtual or auto" ;;
  esac
  [ -n "$(val BACKUP_PASSPHRASE_HOST_FILE)" ] || err "BACKUP_S3_URI is set: off-host backups must be encrypted (BACKUP_PASSPHRASE_HOST_FILE and BACKUP_PASSPHRASE_FILE)"
elif ! $rehearsal; then
  warn "BACKUP_S3_URI is not set: backups stay on this host's disk only (lost with the host)"
fi
# The privacy policy says backups are encrypted before they leave the server: in production that must be true.
if ! $rehearsal && [ -z "$(val BACKUP_PASSPHRASE_HOST_FILE)" ]; then
  err "BACKUP_PASSPHRASE_HOST_FILE is required in production: backups are encrypted with a passphrase file (see env.production.example)"
fi
if [ -n "$(val BACKUP_PASSPHRASE_HOST_FILE)" ]; then
  [ -s "$(val BACKUP_PASSPHRASE_HOST_FILE)" ] || err "BACKUP_PASSPHRASE_HOST_FILE does not exist or is empty on this host"
  [ "$(val BACKUP_PASSPHRASE_FILE)" = "/run/secrets/backup_passphrase" ] || err "BACKUP_PASSPHRASE_FILE must be /run/secrets/backup_passphrase when a host file is given"
fi

echo "Preflight finished: $errors error(s), $warnings warning(s)"
[ "$errors" -eq 0 ]
