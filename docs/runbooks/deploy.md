# Runbook: deploy

Applies to staging and production (same procedure, different server, env file and GitHub environment). Background and
choices: `docs/adr/0041-production-deployment.md`. Everything the owner has to provide first: `docs/launch-checklist.md`.

## One-time server setup

Running on Oracle Cloud's Always Free Ampere A1 (arm64) VMs? The steps below still apply; read `docs/runbooks/oci-always-free.md` first
for the account, network and firewall specifics, the arm64 images and the smaller staging limits (`infra/deploy/env.staging-small.example`).

1. A Linux VM with Docker Engine and the Compose plugin (v2.24 or newer: the overlay uses `!reset` and `!override`), 4 vCPU
   and 8 GB RAM is comfortable (limits: core-api 1.5 GB, ClamAV 2 GB, Postgres 1 GB, the rest under 1.5 GB in total; lower the
   `*_MEM_LIMIT` variables only after watching real usage). Disk: allow for the database, the backup volume (30 days of
   dumps) and rotated logs.
2. Firewall: allow inbound 22 (from your address if you can), 80 and 443 (TCP, and UDP 443 for HTTP/3). Nothing else. Docker
   publishes only Caddy's ports; do not add rules that publish others.
3. DNS: an A (and AAAA) record for the public name (`SITE_ADDRESS`) pointing at the VM. Caddy obtains the certificate on the
   first start; it needs ports 80 and 443 reachable from the internet and the name already resolving.
4. A deploy user that can run Docker (membership of the `docker` group is root-equivalent: use a dedicated key, no password
   login, and keep the key only in the GitHub environment secret). Create `/opt/jobfinder` owned by it.
5. Pull access to the images: on the server run `docker login ghcr.io` once as the deploy user with a **read-only** token
   (`read:packages`), created by a person and stored only in the server's Docker config.
6. The env file: copy `infra/deploy/env.production.example` to `/opt/jobfinder/.env`, `chmod 600`, fill in every value
   (`openssl rand -hex 24` for secrets). Create the backup passphrase file:
   `openssl rand -hex 32 > /opt/jobfinder/backup-passphrase; chown 999:999 /opt/jobfinder/backup-passphrase; chmod 400 ...`,
   set `BACKUP_PASSPHRASE_HOST_FILE` to it and `BACKUP_PASSPHRASE_FILE=/run/secrets/backup_passphrase`, and put a copy in
   the password manager. Without that passphrase an encrypted backup cannot be restored.
7. Run `infra/deploy/preflight.sh /opt/jobfinder/.env` (the first deploy runs it too). Fix every ERROR.
8. GitHub: environments `staging` and `production` with the secrets and variables listed in `.github/workflows/deploy.yml`;
   **required reviewers on `production`** (this is the manual approval); restrict `production` to tags matching `v*`.
9. Webhooks and callbacks (after the first deploy): Stripe `https://<SITE_ADDRESS>/api/core/webhooks/stripe`, Paystack
   `https://<SITE_ADDRESS>/api/core/webhooks/paystack`, Google sign-in authorised origin `https://<SITE_ADDRESS>`.
10. Bucket lifecycle for the off-host backups: `infra/deploy/bucket-lifecycle.json` (backstop at 31 days; the sidecar prunes at
    30). Skip only if the provider cannot do it, and then watch the pruning log lines.

## Releasing

Staging deploys itself: every merge to `main` publishes `sha-<commit>` images and deploys them to staging (if
`STAGING_DEPLOY_ENABLED=true` on that environment). To release:

1. Check staging: open it, run `SMOKE_BASE_URL=https://<staging> infra/deploy/smoke.sh`, read the Sentry and log output.
2. Check the release contains no migration that breaks the backward-compatibility rule (a column dropped or renamed, a type
   narrowed, a NOT NULL added without default while the old code still runs). If it does, split it over two releases.
3. Tag the commit that is on staging: `git tag v1.2.0 <commit> && git push origin v1.2.0`. The `deploy` workflow promotes
   that commit's already-scanned images to `v1.2.0`, then waits for a reviewer to approve the `production` environment.
4. Approve. The workflow copies `infra/` to the server and runs `deploy.sh v1.2.0` there, then smoke-tests the public URL
   from the runner.

## What `deploy.sh TAG` does (on the server)

preflight (refuses unsafe config) -> records the running tag -> pre-deploy backup (`jobfinder-<time>-pre-deploy.dump.enc`) ->
`docker compose pull` -> `up -d --wait` (core-api runs Flyway; `--wait` returns when everything is healthy) -> waits for the
public URL -> `smoke.sh` -> records the tag. If the stack is unhealthy or the smoke test fails, it puts the previous tag back
by itself and exits non-zero (the workflow fails; read its log). Manual run:

    cd /opt/jobfinder && ENV_FILE=/opt/jobfinder/.env infra/deploy/deploy.sh v1.2.0

State (`current_tag`, `previous_tag`, `history`) is in `/opt/jobfinder/.deploy-state/`.

## After a deploy

- `docker compose -p jobfinder ps` (adjust the file arguments as in `deploy-lib.sh`) shows every service healthy, including
  `backup` (healthy means a daily backup newer than 30 hours exists).
- The smoke test result is the evidence; keep the workflow run.
- If the release changes the privacy-relevant behaviour (new data, new subprocessor), update the legal pages and
  `docs/compliance/` in the same release.

## Multi-arch images

Every image tag the workflow publishes is an index with `linux/amd64` and `linux/arm64`, so the same tag deploys to an x86 or an Arm host.
`infra/deploy/check-multiarch.sh` fails the checks when a pinned third-party image is not multi-arch. Details: ADR 0041, addendum 1.

## Staging before production, and a dry run on a laptop

`infra/deploy/rehearsal.sh up|smoke|ports|backup|restore-drill|rollback|load|down` runs the production overlay in an isolated
compose project with random secrets (see ADR 0041). Use it to try a change to the deployment files before it reaches a server.
