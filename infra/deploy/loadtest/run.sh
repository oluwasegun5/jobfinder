#!/usr/bin/env bash
# Runs the k6 load test against the local rehearsal stack (infra/deploy/rehearsal.sh up first).
#   run.sh PROJECT [VUS] [HOLD]      defaults: 10 virtual users, 90 s at peak (modest: the rehearsal runs on one machine)
# k6 runs in the grafana/k6 image, sharing the network namespace of the Caddy container, so it reaches the stack exactly
# as a client does: https://localhost:443 through the proxy. Certificate verification is skipped for that one hop
# because Caddy's local CA is not in the image; a real run against staging verifies normally.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
project="${1:?project name}"
vus="${2:-10}"
hold="${3:-90s}"
dir="${REHEARSAL_DIR:-${TMPDIR:-/tmp}/$project}"
https_port="${REHEARSAL_HTTPS_PORT:-18443}"
mail_port="${REHEARSAL_MAIL_PORT:-18025}"
K6_IMAGE="grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d"

caddy="$(docker ps --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=caddy" --format '{{.Names}}' | head -n 1)"
[ -n "$caddy" ] || { echo "the $project stack is not running" >&2; exit 1; }

# Signup is limited to 5 per client address per hour (ADR 0037), and the smoke tests already used some of them. In this
# throw-away stack only, clear the counters so the test can create its accounts. A real environment keeps its limits:
# there, create the load-test accounts beforehand and pass them in USERS_FILE.
redis="$(docker ps --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=redis" --format '{{.Names}}' | head -n 1)"
docker exec "$redis" sh -c 'export REDISCLI_AUTH="$REDIS_PASSWORD"; for k in $(redis-cli --scan --pattern "rl:*"); do redis-cli del "$k" >/dev/null; done'

mkdir -p "$dir/load"
python3 -I "$here/users.py" --base "https://localhost:$https_port" --mail "http://127.0.0.1:$mail_port" --cafile "$dir/caddy-root.crt" \
  --count "${LOAD_USERS:-5}" --out "$dir/load/users.json"
chmod 644 "$dir/load/users.json"

docker run --rm --network "container:$caddy" -v "$here/jobfinder.js:/work/jobfinder.js:ro" -v "$dir/load:/work/data" \
  -e BASE_URL=https://localhost -e ORIGIN="https://localhost:$https_port" -e USERS_FILE=/work/data/users.json -e VUS="$vus" -e HOLD="$hold" \
  -e P95_MS="${P95_MS:-800}" -e P99_MS="${P99_MS:-2000}" \
  "$K6_IMAGE" run --insecure-skip-tls-verify --summary-export=/work/data/summary.json /work/jobfinder.js | tee "$dir/load/k6-output.txt"
echo "summary: $dir/load/summary.json"
