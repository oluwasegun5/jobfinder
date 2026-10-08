#!/usr/bin/env bash
# Shared settings and helpers for the backup scripts (ADR 0041). Sourced, never run.
# shellcheck shell=bash

: "${BACKUP_DIR:=/backups}"
# How long backups are kept, in days. This is the figure the privacy policy states (RETENTION_DAYS.backups in
# apps/web/src/features/legal/placeholders.ts); a test keeps the two in step. Change both together.
: "${BACKUP_RETENTION_DAYS:=30}"
# A daily backup older than this many hours makes backup-status.sh (and the container health check) fail.
: "${BACKUP_MAX_AGE_HOURS:=30}"
# UTC time of the daily backup, HH:MM.
: "${BACKUP_TIME_UTC:=01:40}"

: "${PGHOST:=postgres}"
: "${PGPORT:=5432}"
: "${PGUSER:=jobfinder}"
: "${PGDATABASE:=jobfinder}"
export PGHOST PGPORT PGUSER PGDATABASE
if [ -n "${PGPASSWORD_FILE:-}" ] && [ -r "$PGPASSWORD_FILE" ]; then
  PGPASSWORD="$(cat "$PGPASSWORD_FILE")"
fi
export PGPASSWORD

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

require_integer() { # name value min
  case "$2" in
    '' | *[!0-9]*) die "$1 must be a whole number, got '$2'" ;;
  esac
  [ "$2" -ge "$3" ] || die "$1 must be at least $3, got '$2'"
}

# Backups of the database; the sidecar files (.sha256) and the in-progress files are not backups.
backup_files() { # prints the paths of finished backups, newest name last
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'jobfinder-*.dump*' ! -name '*.sha256' ! -name '*.partial*' 2>/dev/null | sort
}

# Addressing style of the S3 endpoint: BACKUP_S3_ADDRESSING_STYLE=path|virtual|auto. Empty keeps the client default (the AWS
# CLI already uses path-style for most custom endpoints). Oracle Cloud Object Storage (S3 compatibility API) is documented
# with a path-style endpoint, https://<namespace>.compat.objectstorage.<region>.oci.customer-oci.com, so set "path" there
# (docs/runbooks/oci-always-free.md). The setting goes into a throw-away copy of the image's AWS config for each call: the
# image's own config file (read-only root) is never edited.
aws_s3() { # aws s3 against the configured S3-compatible endpoint (R2, S3, OCI, ...)
  local style="${BACKUP_S3_ADDRESSING_STYLE:-}" cfg="" rc=0
  case "$style" in
    '' | path | virtual | auto) ;;
    *) die "BACKUP_S3_ADDRESSING_STYLE must be path, virtual or auto, got '$style'" ;;
  esac
  if [ -n "$style" ]; then
    cfg="$(mktemp "${TMPDIR:-/tmp}/aws-config.XXXXXX")"
    { [ -z "${AWS_CONFIG_FILE:-}" ] || [ ! -r "$AWS_CONFIG_FILE" ] || cat "$AWS_CONFIG_FILE"
      printf '\n[default]\ns3 =\n    addressing_style = %s\n' "$style"; } > "$cfg"
  fi
  local extra=()
  [ -z "$cfg" ] || extra=(AWS_CONFIG_FILE="$cfg")
  env \
    AWS_ACCESS_KEY_ID="${BACKUP_S3_ACCESS_KEY:?BACKUP_S3_ACCESS_KEY is required with BACKUP_S3_URI}" \
    AWS_SECRET_ACCESS_KEY="${BACKUP_S3_SECRET_KEY:?BACKUP_S3_SECRET_KEY is required with BACKUP_S3_URI}" \
    AWS_DEFAULT_REGION="${BACKUP_S3_REGION:-auto}" \
    AWS_EC2_METADATA_DISABLED=true \
    ${extra[@]+"${extra[@]}"} \
    aws ${BACKUP_S3_ENDPOINT:+--endpoint-url "$BACKUP_S3_ENDPOINT"} "$@" || rc=$?
  [ -z "$cfg" ] || rm -f "$cfg"
  return "$rc"
}
