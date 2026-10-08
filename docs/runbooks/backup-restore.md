# Runbook: backups and restore

## What is backed up
- PostgreSQL only (all application data, including uploaded-file metadata). Redis holds cache and rate-limit counters and is rebuilt on its own.
- Daily `pg_dump -Fc` by the `backup` service at `BACKUP_TIME_UTC`. Each file is verified (`pg_restore --list`), checksummed (sha256) and encrypted with AES-256-CBC + PBKDF2 using the passphrase file (`BACKUP_PASSPHRASE_FILE`, required in production).
- A backup is also taken before every deploy (`deploy.sh`).
- If `BACKUP_S3_*` is set, each file is copied to the S3-compatible bucket under `backups/`.

## Retention (30 days by default)
- `BACKUP_RETENTION_DAYS` (default 30) is enforced by `prune.sh` locally and, if configured, in the bucket. It is the same number the privacy policy states (`RETENTION_DAYS.backups` in `apps/web/src/features/legal/placeholders.ts`). Change both together and update `docs/compliance/data-inventory.md`.
- Bucket: apply `infra/deploy/bucket-lifecycle.json` (expires `backups/` objects after 31 days, one day more than the policy as a backstop). Enable versioning on the bucket only together with a noncurrent-version expiry rule, otherwise deleted backups would outlive the retention promise. Restrict the bucket credentials to the `backups/` prefix; they must not be able to delete other data.
- Not covered: point-in-time recovery. Worst-case data loss is up to 24 hours (since the last daily backup or pre-deploy backup).

## Check backup health
- `docker compose ps backup` shows healthy only if the newest backup is fresher than the freshness limit (`backup-status.sh`).
- `docker compose exec backup /opt/backup/backup-status.sh` prints the newest file and its age.
- Take one now: `docker compose exec backup /opt/backup/backup.sh`.

## Restore into a scratch database (safe, no downtime)
Use this to inspect data or to rehearse. The live database is not touched.

1. Pick a file: `docker compose exec backup ls -1t /backups | head` (or download from the bucket and decrypt with the passphrase file).
2. `docker compose exec backup /opt/backup/restore.sh /backups/<file> --target restore_check`
   (`--drop-existing` replaces an earlier scratch database of the same name). The restore runs in one transaction, so a failure leaves an empty database, never a half-restored one.
3. Inspect with `psql -d restore_check`.
4. Drop it afterwards: `DROP DATABASE restore_check;`

## Disaster: replace the live database
Only for data damage or a schema that the old image cannot run on. Expect downtime.

1. Announce the incident (see `incident.md`). Decide the target backup; everything written after it is lost.
2. Stop the writers: `docker compose stop core-api ai-service web`. Nothing else may be connected to the database.
3. `docker compose exec backup /opt/backup/restore.sh /backups/<file> --replace-live`
   The old database is renamed to `<name>_before_restore_<timestamp>`, not dropped.
4. Deploy the application version that matches the restored schema (`deploy.md`; the tag recorded at backup time is in the file name metadata/log). Flyway applies any newer migrations.
5. Run `infra/deploy/smoke.sh` against the site.
6. Keep the `_before_restore_` database until you are sure, then drop it. It contains personal data and is subject to the same retention promise: drop it within the retention window.

## Re-apply account deletions after any restore
A restored database can contain users who deleted their account after the backup was taken. This is a legal obligation (GDPR/NDPR erasure), not optional.
1. Find deletion requests newer than the backup: from the live `_before_restore_` database (`users` with deletion timestamp after the backup time) or from support records.
2. Re-run the account deletion for each user through the admin deletion path, then record the action in the incident notes.
There is no automation for this yet (listed in the launch checklist).

## Quarterly restore drill
Every quarter, and after any change to the backup scripts:
1. Restore the newest backup into a scratch database as above.
2. Compare row counts of the main tables with live (`users`, `profiles`, `jobs`, `applications`, `documents`).
3. Record date, backup file, result and duration in the on-call log. The scripted version is `infra/deploy/backup/roundtrip-test.sh` (per-table row count and content hash comparison); the local rehearsal runs it with `infra/deploy/rehearsal.sh`.

## Lost passphrase
Encrypted backups cannot be opened without the passphrase file. Keep a copy in the team password manager, separate from the server. Rotation: `secret-rotation.md`.
