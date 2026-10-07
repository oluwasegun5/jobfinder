#!/usr/bin/env python3
"""Checks the observability config without Docker (make observability-check, run in CI).

Dashboards are code: each must parse, have a unique uid, fit the 24-column grid, and use only the provisioned
datasources, and every PromQL expression must have balanced brackets and quotes. The scrape config must cover
core-api and ai-service with the service token, and the compose profile must keep the heavy services out of the
default stack. Standard library only.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent
COMPOSE = ROOT.parent / "docker-compose.yml"
DATASOURCES = {"prometheus", "tempo"}
PAIRS = {"(": ")", "[": "]", "{": "}"}


def balanced(expr: str) -> bool:
    stack: list[str] = []
    quote = ""
    for ch in expr:
        if quote:
            if ch == quote:
                quote = ""
        elif ch in "\"'":
            quote = ch
        elif ch in PAIRS:
            stack.append(PAIRS[ch])
        elif ch in PAIRS.values():
            if not stack or stack.pop() != ch:
                return False
    return not stack and not quote


def check_dashboards() -> list[str]:
    errors: list[str] = []
    uids: set[str] = set()
    files = sorted((ROOT / "grafana" / "dashboards").glob("*.json"))
    if not files:
        errors.append("no dashboards found")
    for path in files:
        try:
            dash = json.loads(path.read_text())
        except json.JSONDecodeError as e:
            errors.append(f"{path.name}: not JSON ({e})")
            continue
        uid = dash.get("uid", "")
        if not re.fullmatch(r"[a-z0-9-]{3,40}", uid) or uid in uids:
            errors.append(f"{path.name}: uid {uid!r} is missing, malformed or repeated")
        uids.add(uid)
        if not dash.get("title"):
            errors.append(f"{path.name}: no title")
        ids: set[int] = set()
        for panel in dash.get("panels", []):
            name = f"{path.name}: panel {panel.get('title')!r}"
            if panel.get("id") in ids:
                errors.append(f"{name}: repeated id")
            ids.add(panel.get("id"))
            grid = panel.get("gridPos", {})
            if grid.get("x", 0) + grid.get("w", 0) > 24:
                errors.append(f"{name}: wider than the 24-column grid")
            if panel.get("type") == "row":
                continue
            if panel.get("datasource", {}).get("uid") not in DATASOURCES:
                errors.append(f"{name}: unknown datasource")
            if not panel.get("targets"):
                errors.append(f"{name}: no queries")
            for target in panel.get("targets", []):
                expr = target.get("expr", "")
                if not expr.strip() or not balanced(expr):
                    errors.append(f"{name}: bad expression {expr!r}")
    return errors


def check_scrape_config() -> list[str]:
    text = (ROOT / "prometheus.yml").read_text()
    errors = []
    for job, path in (("core-api", "/actuator/prometheus"), ("ai-service", "/metrics")):
        block = re.search(rf"job_name: {job}\n(.*?)(?=\n  - job_name|\Z)", text, re.S)
        if not block:
            errors.append(f"prometheus.yml: no job {job}")
            continue
        if f"metrics_path: {path}" not in block.group(1):
            errors.append(f"prometheus.yml: {job} must scrape {path}")
        if "X-Service-Token" not in block.group(1) or "/run/secrets/ai_service_token" not in block.group(1):
            errors.append(f"prometheus.yml: {job} must send the service token from the compose secret")
    return errors


def check_compose_profile() -> list[str]:
    text = COMPOSE.read_text()
    errors = []
    for service in ("tempo", "prometheus", "grafana"):
        block = re.search(rf"^  {service}:\n(.*?)(?=^  \S|^\S|\Z)", text, re.S | re.M)
        if not block or "profiles: [observability]" not in block.group(1):
            errors.append(f"docker-compose.yml: {service} must be in the observability profile")
    return errors


def main() -> int:
    errors = check_dashboards() + check_scrape_config() + check_compose_profile()
    for error in errors:
        print(f"observability-check: {error}", file=sys.stderr)
    if not errors:
        print("observability-check: ok")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
