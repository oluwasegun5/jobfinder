# Runbook: rollback

Pick the smallest tool that fixes the problem.

| Situation | Action |
|---|---|
| The deploy itself failed (unhealthy stack, failed smoke test) | Nothing to do: `deploy.sh` already put the previous tag back. Read the workflow log and `docker compose logs core-api`. If the log says the rollback also failed, go to `incident.md`. |
| The release is live but misbehaves (bug, error rate up) | Application rollback below. |
| Data was damaged or lost (bad migration, bad code that wrote wrong data, accidental delete) | Restore: `backup-restore.md`. Application rollback alone does not bring data back. |
| A migration broke the backward-compatibility rule and the old image cannot run on the new schema | Restore from the pre-deploy backup, then deploy the old tag: `backup-restore.md`, "Disaster: replace the live database". |

## Application rollback (database untouched)

Safe because every migration is backward compatible: the previous image runs against the current schema (ADR 0041).

From GitHub: Actions > `deploy` > Run workflow > environment, and the **previous** tag (for example `v1.1.0`). Production still
asks for approval. Or on the server:

    cd /opt/jobfinder
    ENV_FILE=/opt/jobfinder/.env STATE_DIR=/opt/jobfinder/.deploy-state infra/deploy/rollback.sh        # previous tag
    ENV_FILE=/opt/jobfinder/.env STATE_DIR=/opt/jobfinder/.deploy-state infra/deploy/rollback.sh v1.0.3 # a specific tag

`rollback.sh` starts the images of that tag, waits for health, runs the smoke test and records the tag. It does not take a
backup (nothing is being changed in the database).

## After a rollback

1. Say so in the team channel with the tag and the reason; open an issue for the bad release.
2. Do not re-tag the same version number: fix forward under a new tag. A tag that was deployed is never moved.
3. If the bad release had created data in a new shape, decide with the author whether it needs a repair script.
4. The migrations of the bad release stay applied. The next release must keep working with them or add a corrective
   migration (forward only; never edit an applied migration).
