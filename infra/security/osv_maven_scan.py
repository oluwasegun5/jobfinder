#!/usr/bin/env python3
"""Checks the runtime and test dependencies of core-api against the OSV database (ADR 0037).

Usage: python3 infra/security/osv_maven_scan.py <file from `mvn dependency:list -DoutputFile=...`>
Exit status 1 when any HIGH or CRITICAL advisory applies; lower severities are printed and do not fail the run.
Needs network access to api.osv.dev (no key). Standard library only.
"""
import collections
import json
import re
import sys
import urllib.request

BLOCKING = {"HIGH", "CRITICAL"}


def post(url, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def get(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def main(path):
    deps = set()
    for line in open(path):
        match = re.match(r"^\s*([^:\s]+):([^:]+):([^:]+):(?:([^:]+):)?([^:]+):(\w+)", line)
        if match:
            group, artifact, _type, _classifier, version, _scope = match.groups()
            deps.add((f"{group}:{artifact}", version))
    deps = sorted(deps)
    print(f"dependencies queried: {len(deps)}")
    results = post("https://api.osv.dev/v1/querybatch",
                   {"queries": [{"package": {"name": n, "ecosystem": "Maven"}, "version": v} for n, v in deps]})["results"]
    severities = collections.Counter()
    for (name, version), result in zip(deps, results):
        for vuln in result.get("vulns", []):
            detail = get(f"https://api.osv.dev/v1/vulns/{vuln['id']}")
            severity = (detail.get("database_specific") or {}).get("severity", "UNKNOWN")
            severities[severity] += 1
            print(f"{severity:9} {name}:{version} {vuln['id']} {detail.get('summary', '')[:90]}")
    print("summary:", dict(severities) or "no known vulnerabilities")
    return 1 if any(s in BLOCKING for s in severities) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
