# Launch checklist

Nothing here has been deployed. The P6.5 work was rehearsed only on a throwaway local stack (`infra/deploy/rehearsal.sh`). Items marked OWNER need your accounts or decisions; no value for them is invented in this repository.

## 1. Phase 6 "Done when" status
| Item | Status | Evidence / what is missing |
|---|---|---|
| A user can subscribe and credits apply | Partly verified | Built and tested with provider mocks in earlier P6 tasks. Never exercised against live Paystack or Stripe. Needs OWNER live keys, webhook endpoints registered with the public URL, and one real small test payment in each provider's test mode, then live mode. |
| Load test at target concurrency passes | Not met | A 10-user run passes all thresholds (`docs/load-test-report.md`). PLAN.md defines no target concurrency; OWNER sets it, then re-run against staging. |
| Restore from backup verified | Met (rehearsal) | Backup, verification, encryption, offsite copy, prune, restore into a scratch DB and a replace-live drill ran with per-table row counts and content hashes identical (42 tables plus a probe table). Repeat once on the real server with real storage (first quarterly drill). Gap: no point-in-time recovery. |
| Privacy policy and terms live | Not met | Pages and text exist; company identity, contact, address, governing law and similar are placeholders (`placeholders.ts`). Backup retention is now a real value (30 days). Needs OWNER facts, lawyer review, and a deployed site. |

## 2. Accounts and infrastructure (OWNER)
- [ ] Server (one VM; size for Postgres, Java, Python, Next.js, ClamAV, about 8 GB RAM minimum) and a deploy user with Docker.
- [ ] Domain and DNS: A/AAAA record to the server; ports 80 and 443 open (Caddy obtains certificates). Set `SITE_ADDRESS`, `WEB_BASE_URL`, ACME email.
- [ ] Container registry (GHCR default): `IMAGE_REGISTRY`, pull credentials on the server.
- [ ] Object storage for offsite backups: bucket, scoped key, `BACKUP_S3_*`, apply `infra/deploy/bucket-lifecycle.json`.
- [ ] Email provider (transactional): credentials and sender domain (SPF, DKIM).
- [ ] Paystack and Stripe live keys and webhook secrets.
- [ ] LLM provider key and spending cap.
- [ ] Error tracking DSN (Sentry or similar) if wanted; observability stack choice from P6.3.
- [ ] Uptime monitor on `/` and the health endpoint, with alerting to a person.

## 3. GitHub configuration (OWNER)
- [ ] Environments `staging` and `production`; production with required reviewers (this is the manual approval gate).
- [ ] Environment secrets: deploy SSH key, pinned known_hosts, registry token, host/user, env file contents (names in `infra/deploy/env.production.example`).
- [ ] Branch protection on main; enable the new workflows (`deploy.yml`, `deploy-checks.yml`, `base-images.yml`).
- [ ] First release: tag `v0.1.0` after a staging deploy and `smoke.sh` pass.

## 4. Secrets (OWNER)
- [ ] Generate every secret in `env.production.example` fresh (random, 32+ bytes). Never reuse rehearsal values.
- [ ] Create the backup passphrase file; store a copy in the team password manager.
- [ ] `infra/deploy/preflight.sh` passes on the server.

## 5. Legal and compliance (OWNER)
- [ ] Fill every placeholder in `apps/web/src/features/legal/placeholders.ts` (entity name, address, contacts, jurisdiction); no facts were invented.
- [ ] Lawyer review of privacy policy, terms, DPA template (`docs/compliance/`).
- [ ] Register with the data-protection regulator if required (NDPC in Nigeria) and name the DPO/contact.
- [ ] Confirm backup retention (30 days) is acceptable; if changed, change `BACKUP_RETENTION_DAYS`, `RETENTION_DAYS.backups`, and the data inventory together.

## 6. First-deploy sequence
1. `preflight.sh`, then deploy to staging (workflow, tag-less run).
2. `smoke.sh https://staging...` (health, TLS and headers, signup to AI gate with consent).
3. Take a backup and run a restore drill on staging.
4. Tag `vX.Y.Z`, approve production deploy, run smoke again.
5. Confirm the first scheduled backup appears and the `backup` container is healthy.
6. Register payment webhooks, make a test subscription.

## 7. Known gaps and decisions
- No point-in-time recovery (PLAN mentions it). Daily backups mean up to 24 h data loss. Decide whether to add WAL archiving or move to managed Postgres.
- Deleted accounts are not automatically re-deleted after a restore (manual step in `backup-restore.md`).
- Single VM: no high availability; deploys have a short blip handled by proxy retries. Single-VM instead of the PLAN hosting option is recorded in ADR 0041; the owner must confirm.
- ClamAV is enabled by default in production; its startup needs memory and a signature download. It was configured but not exercised end to end in the rehearsal.
- `management.otlp.tracing.export.enabled` in `application.yml` has no effect on Spring Boot 4.1 (worked around in the overlay).
- No target concurrency in PLAN; AI load not tested with a real provider.
- On-call roster and paging tool undecided (`docs/runbooks/on-call.md`).
- Dependabot/image digest refresh is a weekly manual workflow (`base-images.yml`).
