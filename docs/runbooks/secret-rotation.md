# Runbook: secret rotation

General rule: rotate on a schedule (at least yearly), on staff change, and immediately on suspected exposure. Secrets live only in the server env file (`infra/.env.production`, mode 600, never committed), the backup passphrase file, and GitHub environment secrets. Names are listed in `infra/deploy/env.production.example`. Run `infra/deploy/preflight.sh` after editing the env file; then `deploy.md` to apply.

| Secret | Effect of rotating | Procedure |
|---|---|---|
| `POSTGRES_PASSWORD` | DB connections drop | `ALTER USER ... PASSWORD` in the running DB, update env file, redeploy. Do it in a quiet window. |
| `REDIS_PASSWORD` | Rate-limit counters and cache are lost (acceptable) | Update env file, redeploy (Redis, core-api, ai-service restart together). |
| `RABBITMQ_*` | Queues reconnect | Change user/password in the broker, update env, redeploy. |
| `JWT` signing secret (core-api) | All sessions end; users log in again | Update env, redeploy. Do this first on suspected token theft. |
| `AI_SERVICE_TOKEN` | Internal calls fail until both services restart | Update once in env, redeploy both services together. |
| `ENCRYPTION` / field-encryption keys | Existing ciphertext unreadable if the old key is dropped | Do not just replace. Follow the key-ring procedure in the core-api docs; keep the old key until re-encryption is complete. If none exists yet, treat as a planned change with its own ADR. |
| Payment provider keys and webhook secret | Billing calls fail until updated | Create the new key in the provider dashboard, update env, redeploy, then revoke the old key. |
| LLM provider key | AI features fail until updated | Same: new key, deploy, revoke old. |
| Email provider credentials | Mail stops until updated | Same. |
| `BACKUP_PASSPHRASE_FILE` | Old backups need the old passphrase | Keep the old passphrase until the last backup encrypted with it has aged out (30 days), then destroy it. New backups use the new file. |
| Backup bucket keys | Offsite copies fail until updated | Create new key (scoped to `backups/`), update env, redeploy, check `backup-status.sh`, revoke old key. |
| GitHub secrets (`DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`, registry token) | Deploy workflow fails until updated | Generate new key pair, add the public key to the server's deploy user, update the secret, run a staging deploy, remove the old public key. |

After any rotation: run `infra/deploy/smoke.sh`, confirm the `backup` container is healthy, and note the date in the on-call log. If a secret was exposed in git, rotate first, then clean history; a deleted commit is still a leaked secret.
