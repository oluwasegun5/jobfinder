# Runbook: on-call basics

Status: the roster, paging tool and contact details are not decided; the owner fills them in (launch checklist). Until then the owner is on call.

## Expectations
- Respond to a page within 15 minutes, any day; this is a small team, so keep the rotation to one week at a time.
- Hand over in writing at the end of the week: open incidents, risky deploys, anything flaky.

## What you can do alone
- Check health: `infra/deploy/smoke.sh https://<domain>`, `docker compose ps`.
- Roll back the last release (`rollback.md`).
- Restart one service: `docker compose restart <service>`.
- Take or restore a backup (`backup-restore.md`).

## Ask before you do
- Restoring over the live database, rotating the encryption key, deleting data, changing DNS.

## Daily glance (2 minutes)
1. Backup service healthy and newest backup under 26 hours old.
2. No unhealthy containers; disk below 80 percent.
3. Certificate expiry more than 14 days away (Caddy renews automatically; this catches silent failures).
4. Error rate and latency dashboards (observability stack from P6.3), if enabled.

## Weekly
- Review dependency and image scan results from the CI workflow.
- Check the base-image workflow for pending digest updates.

## Quarterly
- Restore drill (`backup-restore.md`).
- Secret rotation review (`secret-rotation.md`).
- Re-read this runbook; fix what was wrong.

## Log
Keep a plain on-call log (date, who, what happened, what changed) in the team wiki. Restore drills and rotations are recorded there.
