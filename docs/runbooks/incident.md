# Runbook: incident response

## 1. Triage (first 10 minutes)
1. Acknowledge the alert; name one incident lead.
2. Classify:
   - **Outage or degraded**: site down, 5xx, slow. Go to section 2.
   - **Data loss or corruption**: go to `backup-restore.md`; stop writers first to limit damage.
   - **Security** (suspected breach, leaked secret, abuse): go to section 3.
3. Open a notes document with timestamps. Write down every action you take.

## 2. Outage checklist
- `infra/deploy/smoke.sh https://<domain>` tells you which layer fails (DNS/TLS, proxy, web, API, AI gate).
- `docker compose ps` for unhealthy services; `docker compose logs --since 30m <service>`.
- Recent deploy? Roll back first, investigate later (`rollback.md`).
- Disk full: `df -h`, `docker system df`; prune old images, never volumes.
- Certificate problems: Caddy logs (`docker compose logs caddy`); ports 80 and 443 must be reachable for issuance.
- Database down or slow: `docker compose logs postgres`; if the data directory is damaged, `backup-restore.md`.
- AI provider failing: AI features degrade (the gate returns an error); the rest of the product keeps working. Wait it out or switch provider config via env and redeploy.

## 3. Security incident
1. Contain: rotate the exposed secret (`secret-rotation.md`); if tokens may be stolen, rotate the JWT secret (ends all sessions); block abusive IPs at the provider firewall.
2. Preserve evidence: copy logs before they rotate (`docker compose logs --no-color > incident-<date>.log`); do not delete anything.
3. Assess whether personal data was exposed. If yes, regulator and user notification duties apply (NDPR/NDPC in Nigeria, GDPR if EU users): the deadline is 72 hours from awareness. Escalate to the data-protection contact named in the privacy policy. These contacts are placeholders until the owner fills them in (launch checklist).
4. Recover, then write the post-incident review.

## 4. Communicate
Post short updates (what we know, what we do, next update time) to the status channel every 30 minutes during an active incident. Do not speculate about cause publicly.

## 5. Post-incident review (within 5 working days)
Timeline, impact (users, duration), root cause, what worked, what did not, action items with owners. Blameless. File actions as issues; add a runbook change if a step was missing.
