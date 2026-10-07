#!/usr/bin/env bash
# Smoke test of a deployed stack, from the outside (docs/runbooks/deploy.md). Needs bash, curl and python3.
#
#   SMOKE_BASE_URL=https://app.example.com infra/deploy/smoke.sh
#
# Always checked: TLS and certificate, http -> https redirect, response headers, health, that internal paths are not
# reachable, and that signup without the AI-processing consent is refused. The path "sign up with consent -> verify ->
# AI consent gate" additionally needs a way to read the verification email or an existing verified account:
#
#   SMOKE_MAIL_API   Mailpit-compatible API base URL (local rehearsal, staging with a catch-all inbox). A fresh account is
#                    created and deleted again (set SMOKE_KEEP_USER=1 to keep it; SMOKE_CREDENTIALS_FILE then receives it).
#   SMOKE_USER_EMAIL / SMOKE_USER_PASSWORD
#                    an existing verified account used instead (production: a dedicated smoke account). Its consent is
#                    toggled and put back as found; nothing is created.
#
# Without either, that part is reported as SKIPPED, and the exit status is 2 when SMOKE_REQUIRE_AUTHED=1, else 0.
# Other settings: SMOKE_HTTP_URL (default: derived from the base URL), SMOKE_CA_CERT (extra trusted CA, the local
# rehearsal's), SMOKE_CERT_MIN_SECONDS (default 7 days; the local CA issues 12 hour certificates), SMOKE_INSECURE=1 (skip certificate verification; never for a real deployment).
set -uo pipefail

BASE="${SMOKE_BASE_URL:?set SMOKE_BASE_URL to the public https URL}"
BASE="${BASE%/}"
case "$BASE" in https://*) ;; *) echo "SMOKE_BASE_URL must be https://..." >&2; exit 64 ;; esac
hostport="${BASE#https://}"
host="${hostport%%:*}"
port="443"
case "$hostport" in *:*) port="${hostport##*:}" ;; esac
HTTP_URL="${SMOKE_HTTP_URL:-http://$host}"

curl_opts=(--silent --show-error --max-time 20)
[ -n "${SMOKE_CA_CERT:-}" ] && curl_opts+=(--cacert "$SMOKE_CA_CERT")
[ "${SMOKE_INSECURE:-0}" = "1" ] && curl_opts+=(--insecure)

work="$(mktemp -d)"
cleanup_items=()
cleanup() { for c in "${cleanup_items[@]:-}"; do [ -n "$c" ] && eval "$c" || true; done; rm -rf "$work"; }
trap cleanup EXIT

pass=0; fail=0; skip=0
ok()   { pass=$((pass + 1)); printf '  PASS  %s\n' "$1"; }
bad()  { fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; }
skp()  { skip=$((skip + 1)); printf '  SKIP  %s\n' "$1"; }
check() { # description, command...
  local d="$1"; shift
  if "$@"; then ok "$d"; else bad "$d"; fi
}

# status BODYFILE [curl args...]  -> prints the HTTP status; body in $work/<BODYFILE>
status() { local out="$1"; shift; curl "${curl_opts[@]}" -o "$work/$out" -w '%{http_code}' "$@" 2>/dev/null || echo 000; }
json_get() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get(sys.argv[2],""))' "$1" "$2" 2>/dev/null; }
rand() { python3 -c 'import secrets; print(secrets.token_hex(int(__import__("sys").argv[1])))' "$1"; }

echo "Smoke test: $BASE"

echo "TLS and edge"
code="$(status home -D "$work/home.h" "$BASE/")"
check "GET / over verified TLS returns 200 (got $code)" test "$code" = "200"
if command -v openssl >/dev/null 2>&1; then
  sclient=(openssl s_client -connect "$host:$port" -servername "$host")
  [ -n "${SMOKE_CA_CERT:-}" ] && sclient+=(-CAfile "$SMOKE_CA_CERT")
  if echo | "${sclient[@]}" 2>/dev/null | openssl x509 -noout -checkend "${SMOKE_CERT_MIN_SECONDS:-604800}" >/dev/null 2>&1; then
    ok "certificate is valid for at least ${SMOKE_CERT_MIN_SECONDS:-604800} more seconds"
  else
    bad "certificate expires within ${SMOKE_CERT_MIN_SECONDS:-604800} seconds (or could not be read)"
  fi
else
  skp "certificate expiry (openssl not installed)"
fi
if [ "$host" != "localhost" ] || [ -n "${SMOKE_HTTP_URL:-}" ]; then
  rcode="$(status redirect -D "$work/redirect.h" "$HTTP_URL/")"
  loc="$(grep -i '^location:' "$work/redirect.h" 2>/dev/null | tr -d '\r' | head -1)"
  case "$rcode" in 301 | 302 | 307 | 308) check "http redirects to https (got $rcode)" sh -c 'echo "$1" | grep -qi "^location: https://"' _ "$loc" ;; *) bad "http does not redirect (got $rcode)" ;; esac
fi

hdr() { grep -i "^$1:" "$work/home.h" 2>/dev/null | tr -d '\r' | head -1; }
echo "Response headers"
check "Strict-Transport-Security with a long max-age" sh -c 'echo "$1" | grep -Eqi "max-age=([3-9][0-9]{7}|[0-9]{9,})"' _ "$(hdr strict-transport-security)"
check "Content-Security-Policy with nonce, frame-ancestors none, no script unsafe-inline" sh -c 'h="$1"; echo "$h" | grep -qi "nonce-" && echo "$h" | grep -qi "frame-ancestors .none." && ! echo "$h" | grep -Eqi "script-src[^;]*unsafe-inline"' _ "$(hdr content-security-policy)"
check "X-Content-Type-Options: nosniff" sh -c 'echo "$1" | grep -qi nosniff' _ "$(hdr x-content-type-options)"
check "no X-Powered-By header" test -z "$(hdr x-powered-by)"
check "Server header does not name a product version" sh -c '! echo "$1" | grep -Eqi "[0-9]+\.[0-9]+"' _ "$(hdr server)"

post_json_pre() { status "$1" -X POST -H 'Content-Type: application/json' -d "$3" "$BASE/api/core$2"; }
echo "Health and closed doors"
code="$(status health "$BASE/api/core/actuator/health")"
check "core-api health through the proxy is UP (got $code)" sh -c 'test "$1" = 200 && grep -q "\"UP\"" "$2"' _ "$code" "$work/health"
for path in /api/core/actuator/prometheus /api/core/actuator/env /api/core/internal/v1/billing/usage /api/core/v3/api-docs /api/core/swagger-ui/index.html; do
  code="$(status closed "$BASE$path")"
  check "$path is not served to the public (got $code)" sh -c 'case "$1" in 401|403|404) exit 0;; *) exit 1;; esac' _ "$code"
done

for provider in stripe paystack; do
  code="$(post_json_pre webhook_$provider "/webhooks/$provider" '{}')"
  check "payment webhook /webhooks/$provider is reachable and rejects an unsigned call (got $code)" sh -c 'case "$1" in 400|401|403) exit 0;; *) exit 1;; esac' _ "$code"
done

echo "Signup requires the AI-processing consent"
email="smoke-$(rand 6)@example.test"
password="Sm0ke-$(rand 12)"
post_json() { # outfile path json [extra curl args]
  local out="$1" path="$2" body="$3"; shift 3
  status "$out" -X POST -H 'Content-Type: application/json' -H "Origin: $BASE" -d "$body" "$@" "$BASE/api/core$path"
}
code="$(post_json nosignup /auth/signup "{\"email\":\"$email\",\"password\":\"$password\",\"aiProcessingConsent\":false}")"
check "signup without consent is refused with 400 (got $code)" test "$code" = "400"

authed=""
token=""
created_user=""
if [ -n "${SMOKE_USER_EMAIL:-}" ] && [ -n "${SMOKE_USER_PASSWORD:-}" ]; then
  authed="existing"
  email="$SMOKE_USER_EMAIL"; password="$SMOKE_USER_PASSWORD"
elif [ -n "${SMOKE_MAIL_API:-}" ]; then
  authed="fresh"
  code="$(post_json signup /auth/signup "{\"email\":\"$email\",\"password\":\"$password\",\"aiProcessingConsent\":true}")"
  check "signup with consent is accepted with 202 (got $code)" test "$code" = "202"
  [ "$code" = "202" ] || authed=""
fi

if [ "$authed" = "fresh" ]; then
  created_user="$email"
  vtoken=""
  for _ in $(seq 1 30); do
    curl --silent --max-time 10 -G --data-urlencode "query=to:$email" "$SMOKE_MAIL_API/api/v1/search" -o "$work/mails.json" 2>/dev/null || true
    mid="$(python3 -c 'import json,sys
try:
    m=json.load(open(sys.argv[1])).get("messages") or []
    print(m[0]["ID"] if m else "")
except Exception:
    print("")' "$work/mails.json")"
    if [ -n "$mid" ]; then
      curl --silent --max-time 10 "$SMOKE_MAIL_API/api/v1/message/$mid" -o "$work/mail.json" 2>/dev/null || true
      vtoken="$(python3 -c 'import json,re,sys
t=json.load(open(sys.argv[1])).get("Text","")
m=re.search(r"token=([A-Za-z0-9_%\-\.~]+)", t)
print(m.group(1) if m else "")' "$work/mail.json")"
      [ -n "$vtoken" ] && break
    fi
    sleep 1
  done
  check "verification email arrived with a token" test -n "$vtoken"
  if [ -n "$vtoken" ]; then
    code="$(post_json verify /auth/verify-email "{\"token\":\"$vtoken\"}")"
    check "email verification returns 204 (got $code)" test "$code" = "204"
  fi
fi

if [ -n "$authed" ]; then
  code="$(post_json login /auth/login "{\"email\":\"$email\",\"password\":\"$password\"}")"
  check "login returns 200 (got $code)" test "$code" = "200"
  token="$(json_get "$work/login" accessToken)"
  if [ -z "$token" ]; then
    bad "login returned an access token"
  else
    auth=(-H "Authorization: Bearer $token" -H "Origin: $BASE")
    pdf="$work/cv.pdf"
    printf '%%PDF-1.4\n%% synthetic smoke-test file, no personal data\n1 0 obj<<>>endobj\ntrailer<<>>\n%%%%EOF\n' > "$pdf"

    if [ "$authed" = "existing" ]; then
      status was_consent "${auth[@]}" "$BASE/api/core/me/consent" >/dev/null
      was="$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1])).get("aiProcessing")).lower())' "$work/was_consent")"
      restore_cmd="curl ${curl_opts[*]} -o /dev/null -X $([ "$was" = true ] && echo PUT || echo DELETE) -H 'Authorization: Bearer $token' -H 'Origin: $BASE' '$BASE/api/core/me/consent/ai'"
      cleanup_items+=("$restore_cmd")
    fi

    code="$(status me "${auth[@]}" "$BASE/api/core/auth/me")"
    check "GET /auth/me works with the access token (got $code)" test "$code" = "200"
    if [ "$authed" = "fresh" ]; then
      check "the new account carries the consent given at signup" sh -c 'grep -q "\"aiConsent\":true" "$1"' _ "$work/me"
    fi

    code="$(status withdraw -X DELETE "${auth[@]}" "$BASE/api/core/me/consent/ai")"
    check "consent can be withdrawn (got $code)" test "$code" = "200"
    code="$(status upload_blocked -X POST "${auth[@]}" -F "file=@$pdf;type=application/pdf;filename=smoke.pdf" "$BASE/api/core/resumes")"
    check "AI gate: CV upload is refused with 403 ai_consent_required while consent is off (got $code)" sh -c 'test "$1" = 403 && grep -q ai_consent_required "$2"' _ "$code" "$work/upload_blocked"

    code="$(status grant -X PUT "${auth[@]}" "$BASE/api/core/me/consent/ai")"
    check "consent can be given again (got $code)" test "$code" = "200"
    code="$(status upload_ok -X POST "${auth[@]}" -F "file=@$pdf;type=application/pdf;filename=smoke.pdf" "$BASE/api/core/resumes")"
    check "AI gate: CV upload passes the scan and reaches object storage once consent is on (got $code)" test "$code" = "201"
    rid="$(json_get "$work/upload_ok" id)"
    if [ -n "$rid" ]; then
      code="$(status rm_cv -X DELETE "${auth[@]}" "$BASE/api/core/resumes/$rid")"
      check "the test CV is deleted again (got $code)" sh -c 'test "$1" = 204 || test "$1" = 200' _ "$code"
    fi

    if [ "$authed" = "fresh" ]; then
      if [ "${SMOKE_KEEP_USER:-0}" = "1" ]; then
        if [ -n "${SMOKE_CREDENTIALS_FILE:-}" ]; then
          umask 077; printf '%s\n%s\n' "$email" "$password" > "$SMOKE_CREDENTIALS_FILE"
        fi
        skp "the smoke account is kept (SMOKE_KEEP_USER=1)"
      else
        code="$(status del_user -X DELETE "${auth[@]}" "$BASE/api/core/me")"
        check "the smoke account is deleted again (got $code)" test "$code" = "204"
      fi
    fi
  fi
else
  skp "sign up with consent -> verify -> AI gate (set SMOKE_MAIL_API, or SMOKE_USER_EMAIL and SMOKE_USER_PASSWORD)"
fi

echo
echo "Result: $pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ] || exit 1
if [ -z "$authed" ] && [ "${SMOKE_REQUIRE_AUTHED:-0}" = "1" ]; then exit 2; fi
exit 0
