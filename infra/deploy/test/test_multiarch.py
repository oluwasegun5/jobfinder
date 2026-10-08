"""Tests of infra/deploy/check-multiarch.sh and of the multi-arch build wiring (ADR 0041, OCI Ampere A1 addendum).
No network: the script reads fixture manifests (MULTIARCH_MANIFEST_DIR) instead of a registry."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from common import DEPLOY, INFRA, REPO

SCRIPT = DEPLOY / "check-multiarch.sh"
BASH = shutil.which("bash")
DIGEST = "sha256:" + "a" * 64


def platform(arch: str, os_name: str = "linux", variant: str | None = None) -> dict:
    p = {"os": os_name, "architecture": arch}
    if variant:
        p["variant"] = variant
    return {"mediaType": "application/vnd.oci.image.manifest.v1+json", "digest": DIGEST, "size": 1, "platform": p}


def index(*archs: str, attestations: bool = True) -> dict:
    manifests = [platform(a, variant="v8" if a == "arm64" else None) for a in archs]
    if attestations:  # BuildKit provenance/SBOM manifests carry platform unknown/unknown
        manifests += [platform("unknown", "unknown") for _ in archs]
    return {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": manifests}


SINGLE = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.manifest.v1+json", "config": {}, "layers": []}


def run(*args: str, env: dict | None = None) -> subprocess.CompletedProcess:
    full = {"PATH": os.environ["PATH"], "HOME": os.environ.get("HOME", "/tmp"), **(env or {})}
    return subprocess.run([BASH, str(SCRIPT), *args], capture_output=True, text=True, env=full)


class ParseManifest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def parse(self, doc, **env):
        f = self.dir / "m.json"
        f.write_text(doc if isinstance(doc, str) else json.dumps(doc))
        return run("--parse", str(f), env=env)

    def test_an_index_with_amd64_and_arm64_passes(self):
        result = self.parse(index("amd64", "arm64"))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("linux/arm64", result.stdout)

    def test_extra_platforms_do_not_matter(self):
        self.assertEqual(self.parse(index("amd64", "arm64", "s390x", "ppc64le")).returncode, 0)

    def test_an_amd64_only_index_fails_and_names_the_missing_platform(self):
        # Exactly the clamav/clamav:1.4 case: one real platform plus its attestation manifest.
        result = self.parse(index("amd64"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing linux/arm64", result.stdout)

    def test_attestation_manifests_do_not_count_as_a_platform(self):
        # Only unknown/unknown entries must never satisfy the requirement.
        result = self.parse(index("unknown"), REQUIRED_PLATFORMS="linux/arm64")
        self.assertEqual(result.returncode, 1)

    def test_an_arm64_only_index_fails_because_amd64_is_required_too(self):
        result = self.parse(index("arm64"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing linux/amd64", result.stdout)

    def test_a_single_platform_manifest_fails(self):
        result = self.parse(SINGLE)
        self.assertEqual(result.returncode, 1)
        self.assertIn("single-platform", result.stdout)

    def test_garbage_fails(self):
        self.assertEqual(self.parse("<html>rate limited</html>").returncode, 1)
        self.assertEqual(self.parse("").returncode, 1)

    def test_the_required_platforms_are_configurable(self):
        self.assertEqual(self.parse(index("amd64"), REQUIRED_PLATFORMS="linux/amd64").returncode, 0)


class RepositoryScan(unittest.TestCase):
    """The reference collection and the whole-repository run, against fixture manifests."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.refs = run("--list").stdout.split()

    def tearDown(self):
        self.tmp.cleanup()

    def write_fixtures(self, bad: str | None = None, skip: str | None = None):
        for ref in self.refs:
            if ref == skip:
                continue
            doc = index("amd64") if ref == bad else index("amd64", "arm64")
            (self.dir / (re.sub(r"[/:@]", "_", ref) + ".json")).write_text(json.dumps(doc))

    def scan(self):
        return run(env={"MULTIARCH_MANIFEST_DIR": str(self.dir)})

    def test_every_pinned_reference_of_the_production_files_is_checked(self):
        self.assertTrue(self.refs)
        pinned = set()
        for f in [*INFRA.glob("docker/*.Dockerfile"), INFRA / "docker-compose.yml", INFRA / "docker-compose.prod.yml"]:
            pinned |= set(re.findall(r"[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}", f.read_text()))
        self.assertTrue(pinned)
        self.assertLessEqual(pinned, set(self.refs))

    def test_every_dockerfile_base_image_is_checked(self):
        for f in INFRA.glob("docker/*.Dockerfile"):
            for line in f.read_text().splitlines():
                m = re.match(r"FROM\s+(\S+)", line)
                if m:
                    self.assertIn(m.group(1), self.refs, f"{f.name}: {m.group(1)}")

    def test_no_templated_reference_is_looked_up(self):
        self.assertFalse([r for r in self.refs if "$" in r])

    def test_passes_when_everything_is_multi_arch(self):
        self.write_fixtures()
        result = self.scan()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_one_amd64_only_image_fails_the_run_and_is_named(self):
        victim = next(r for r in self.refs if r.startswith("clamav/"))
        self.write_fixtures(bad=victim)
        result = self.scan()
        self.assertEqual(result.returncode, 1)
        self.assertIn(f"FAIL {victim}", result.stdout)

    def test_an_unreadable_manifest_fails_instead_of_passing(self):
        self.write_fixtures(skip=self.refs[0])
        result = self.scan()
        self.assertEqual(result.returncode, 1)
        self.assertIn("cannot read the manifest", result.stdout)

    def test_the_production_stack_has_no_amd64_only_clamav_tag(self):
        # clamav/clamav:<version> (Alpine) is amd64 only; the -debian tags are multi-arch.
        for ref in self.refs:
            if ref.startswith("clamav/clamav:"):
                self.assertRegex(ref, r":\d+\.\d+-debian@sha256:", ref)


class Wiring(unittest.TestCase):
    def read(self, rel: str) -> str:
        return (REPO / rel).read_text()

    def test_the_deploy_workflow_builds_both_platforms_and_publishes_one_manifest_per_tag(self):
        text = self.read(".github/workflows/deploy.yml")
        self.assertIn("linux/amd64", text)
        self.assertIn("linux/arm64", text)
        self.assertIn("ubuntu-24.04-arm", text)
        self.assertIn("imagetools create", text)
        self.assertNotRegex(text, r"platforms:\s*linux/arm64\s*$")  # never an arm64-only publish

    def test_every_image_is_scanned_per_platform(self):
        text = self.read(".github/workflows/deploy.yml")
        self.assertIn("aquasec/trivy", text)
        self.assertRegex(text, r"matrix\.platform|matrix\.arch")

    def test_provenance_and_sbom_stay_on_and_the_registry_login_is_unchanged(self):
        text = self.read(".github/workflows/deploy.yml")
        self.assertIn("provenance: true", text)
        self.assertIn("sbom: true", text)
        self.assertIn("registry: ghcr.io", text)
        self.assertIn("secrets.GITHUB_TOKEN", text)

    def test_the_multiarch_check_runs_in_ci_and_in_the_weekly_digest_job(self):
        self.assertIn("check-multiarch.sh", self.read(".github/workflows/deploy-checks.yml"))
        self.assertIn("check-multiarch.sh", self.read(".github/workflows/base-images.yml"))
        self.assertIn("test_multiarch", self.read(".github/workflows/deploy-checks.yml"))

    def test_the_script_is_executable_and_valid_bash(self):
        self.assertTrue(os.access(SCRIPT, os.X_OK))
        self.assertEqual(subprocess.run([BASH, "-n", str(SCRIPT)], capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
