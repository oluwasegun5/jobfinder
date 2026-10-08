#!/usr/bin/env bash
# Fails when an image the production stack depends on is not a multi-architecture index that includes linux/amd64 AND
# linux/arm64 (ADR 0041, addendum for OCI Always Free Ampere A1 hosts, docs/runbooks/oci-always-free.md).
#
#   check-multiarch.sh                  check every image reference in infra/docker/*.Dockerfile, infra/docker-compose.yml
#                                       and infra/docker-compose.prod.yml that is a literal name (no ${VAR}); the digest
#                                       pinned with @sha256: is the one inspected, so a digest that points at a single
#                                       platform manifest, or at an index without arm64, is an error
#   check-multiarch.sh --list           print the references that would be checked
#   check-multiarch.sh --ref REF        check one reference (used by the digest refresh to verify a candidate)
#   check-multiarch.sh --parse FILE     check a manifest JSON file (as printed by `docker buildx imagetools inspect --raw`)
#
# Environment:
#   REQUIRED_PLATFORMS   space separated, default "linux/amd64 linux/arm64"
#   MULTIARCH_MANIFEST_DIR   offline mode for tests: the manifest of REF is read from $DIR/<REF with / : @ replaced by _>.json
#                            instead of the registry
# Needs python3 (parsing only) and, online, `docker buildx imagetools` (a registry read; nothing is pulled or run).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
REQUIRED_PLATFORMS="${REQUIRED_PLATFORMS:-linux/amd64 linux/arm64}"

# Reads a manifest JSON on stdin. Prints the platforms found (attestation manifests, which have platform unknown/unknown,
# are ignored) and exits 0 when every required platform is present in a manifest list / OCI index, 1 otherwise.
parse_manifest() { # label
  REQUIRED="$REQUIRED_PLATFORMS" LABEL="$1" python3 -c "$PARSER"
}
PARSER='
import json, os, sys
label, required = os.environ["LABEL"], os.environ["REQUIRED"].split()
try:
    doc = json.load(sys.stdin)
except ValueError as exc:
    print("FAIL " + label + ": not a manifest (" + str(exc) + ")")
    sys.exit(1)
manifests = doc.get("manifests")
if manifests is None:
    kind = doc.get("mediaType", "no mediaType")
    print("FAIL " + label + ": single-platform manifest (" + kind + "), not a multi-arch index")
    sys.exit(1)
found = set()
for m in manifests:
    p = m.get("platform") or {}
    if p.get("os") in (None, "unknown") or p.get("architecture") in (None, "unknown"):
        continue
    found.add(p["os"] + "/" + p["architecture"])
missing = [r for r in required if r not in found]
listed = ", ".join(sorted(found)) or "none"
if missing:
    print("FAIL " + label + ": platforms [" + listed + "], missing " + " ".join(missing))
    sys.exit(1)
print("ok   " + label + ": [" + listed + "]")
'

# Literal image references (name:tag[@digest]) used by the production stack and its base images.
list_refs() {
  {
    sed -n -E 's/^[[:space:]]*image:[[:space:]]*([^[:space:]#]+).*/\1/p' "$repo/infra/docker-compose.yml" "$repo/infra/docker-compose.prod.yml"
    sed -n -E 's/^FROM[[:space:]]+([^[:space:]]+).*/\1/p' "$repo"/infra/docker/*.Dockerfile
  } | grep -v '\$' | grep -vx 'scratch' | sort -u
}

fetch_manifest() { # ref
  if [ -n "${MULTIARCH_MANIFEST_DIR:-}" ]; then
    f="$MULTIARCH_MANIFEST_DIR/$(printf '%s' "$1" | tr '/:@' '___').json"
    [ -f "$f" ] || { echo "no fixture manifest for $1 ($f)" >&2; return 1; }
    cat "$f"
  else
    # Registries rate-limit anonymous reads now and then: retry a few times before calling it a failure.
    for attempt in 1 2 3; do
      docker buildx imagetools inspect --raw "$1" 2> /tmp/.multiarch-err.$$ && { rm -f /tmp/.multiarch-err.$$; return 0; }
      [ "$attempt" -lt 3 ] && sleep $((attempt * 3))
    done
    cat /tmp/.multiarch-err.$$ >&2; rm -f /tmp/.multiarch-err.$$
    return 1
  fi
}

check_ref() { # ref
  # The digest, when pinned, is what gets inspected: that is the content the build and the servers will use.
  case "$1" in
    *@sha256:*) ;;
    *) echo "note $1: not pinned by digest; the tag is checked instead" >&2 ;;
  esac
  if ! manifest="$(fetch_manifest "$1" 2>&1)"; then
    echo "FAIL $1: cannot read the manifest: ${manifest:0:200}"; return 1
  fi
  printf '%s' "$manifest" | parse_manifest "$1"
}

case "${1:-}" in
  --list) list_refs ;;
  --parse) [ $# -eq 2 ] || { echo "usage: $0 --parse FILE" >&2; exit 64; }; parse_manifest "$2" < "$2" ;;
  --ref) [ $# -eq 2 ] || { echo "usage: $0 --ref REF" >&2; exit 64; }; check_ref "$2" ;;
  "")
    refs="$(list_refs)"
    [ -n "$refs" ] || { echo "no image references found" >&2; exit 1; }
    bad=0
    while read -r ref; do check_ref "$ref" || bad=1; done <<< "$refs"
    if [ "$bad" -ne 0 ]; then
      echo "At least one image is not available for: $REQUIRED_PLATFORMS" >&2
      exit 1
    fi
    ;;
  *) sed -n 2,19p "$0"; exit 64 ;;
esac
