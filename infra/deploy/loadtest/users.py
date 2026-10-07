#!/usr/bin/env python3
"""Creates verified throw-away accounts for the load test (needs a Mailpit-compatible API to read the verification mail).

    users.py --base https://localhost:18443 --mail http://127.0.0.1:18025 --cafile ca.crt --count 5 --out users.json

The accounts use @example.test addresses and random passwords; the k6 script deletes them again in its teardown.
"""
import argparse
import json
import re
import secrets
import ssl
import sys
import time
import urllib.parse
import urllib.request


def request(url, ctx, data=None, headers=None, method=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(url, data=body, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:  # noqa: PERF203
        return e.code, None


def verification_token(mail, email, ctx):
    for _ in range(40):
        _, found = request(f"{mail}/api/v1/search?query=" + urllib.parse.quote(f"to:{email}"), ctx)
        messages = (found or {}).get("messages") or []
        if messages:
            _, msg = request(f"{mail}/api/v1/message/{messages[0]['ID']}", ctx)
            match = re.search(r"token=([A-Za-z0-9_%\-\.~]+)", (msg or {}).get("Text", ""))
            if match:
                return match.group(1)
        time.sleep(1)
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True)
    p.add_argument("--mail", required=True)
    p.add_argument("--cafile")
    p.add_argument("--count", type=int, default=5)
    p.add_argument("--out", required=True)
    a = p.parse_args()
    ctx = ssl.create_default_context(cafile=a.cafile)
    api = a.base.rstrip("/") + "/api/core"
    origin = {"Origin": a.base.rstrip("/")}
    users = []
    for _ in range(a.count):
        email = f"load-{secrets.token_hex(5)}@example.test"
        password = "Ld-" + secrets.token_hex(10)
        status, _ = request(f"{api}/auth/signup", ctx, {"email": email, "password": password, "aiProcessingConsent": True}, origin)
        if status != 202:
            sys.exit(f"signup failed: HTTP {status}")
        token = verification_token(a.mail.rstrip("/"), email, ctx)
        if not token:
            sys.exit("no verification email arrived")
        status, _ = request(f"{api}/auth/verify-email", ctx, {"token": token}, origin)
        if status != 204:
            sys.exit(f"verification failed: HTTP {status}")
        users.append({"email": email, "password": password})
    with open(a.out, "w") as f:
        json.dump(users, f)
    print(f"created {len(users)} accounts")


if __name__ == "__main__":
    main()
