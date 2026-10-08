"""The memory limits of the production overlay against the OCI Always Free Ampere A1 hosts (docs/runbooks/oci-always-free.md):
the defaults must fit the 12 GB production VM with room for the OS, and the staging-small profile must fit 8 GB."""
from __future__ import annotations

import re
import tempfile
import unittest
from pathlib import Path

from common import DEPLOY, INFRA, compose_config, docker_available, fixture_env, write_env

GIB = 1024**3
SMALL = DEPLOY / "env.staging-small.example"


def parse_env(path: Path) -> dict[str, str]:
    out = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            out[key] = value
    return out


def limit_bytes(value) -> int:
    if isinstance(value, int):
        return value
    m = re.fullmatch(r"(\d+)([kmg]?)b?", str(value).lower())
    assert m, value
    return int(m.group(1)) * {"": 1, "k": 1024, "m": 1024**2, "g": 1024**3}[m.group(2)]


@unittest.skipUnless(docker_available(), "docker compose is not available")
class ResourceBudget(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        base = fixture_env()
        cls.default = compose_config(write_env(Path(cls.tmp.name) / "default.env", base))
        cls.small = compose_config(write_env(Path(cls.tmp.name) / "small.env", {**base, **parse_env(SMALL)}))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    @staticmethod
    def running(cfg):
        return {n: s for n, s in cfg["services"].items() if not s.get("profiles")}

    def total(self, cfg) -> int:
        return sum(limit_bytes(s["mem_limit"]) for s in self.running(cfg).values())

    def test_every_running_service_has_a_memory_limit(self):
        for cfg in (self.default, self.small):
            for name, svc in self.running(cfg).items():
                self.assertIn("mem_limit", svc, name)

    def test_the_default_limits_fit_the_12_gb_production_vm_with_room_for_the_os(self):
        total = self.total(self.default)
        self.assertLessEqual(total, 8 * GIB, f"{total / GIB:.2f} GiB of limits leaves less than 4 GiB of 12 GB for the OS and cache")

    def test_the_staging_small_profile_fits_an_8_gb_vm(self):
        total = self.total(self.small)
        self.assertLessEqual(total, 6 * GIB, f"{total / GIB:.2f} GiB of limits leaves less than 2 GiB of 8 GB")
        self.assertLess(total, self.total(self.default))

    def test_the_staging_small_profile_keeps_what_each_service_needs_to_run(self):
        svc = self.running(self.small)
        self.assertGreaterEqual(limit_bytes(svc["clamav"]["mem_limit"]), int(1.5 * GIB))  # signature database
        self.assertGreaterEqual(limit_bytes(svc["core-api"]["mem_limit"]), 1 * GIB)  # JVM heap is 25% of the limit
        self.assertGreaterEqual(limit_bytes(svc["redis"]["mem_limit"]), 192 * 1024**2)  # maxmemory 192mb in the overlay
        self.assertGreaterEqual(limit_bytes(svc["postgres"]["mem_limit"]), 512 * 1024**2)

    def test_the_default_clamav_limit_is_not_below_what_it_needs(self):
        self.assertGreaterEqual(limit_bytes(self.running(self.default)["clamav"]["mem_limit"]), int(1.5 * GIB))

    def test_the_profile_only_sets_variables_the_overlay_reads(self):
        overlay = (INFRA / "docker-compose.prod.yml").read_text()
        for key in parse_env(SMALL):
            self.assertRegex(key, r"^[A-Z_]+_(MEM_LIMIT|CPUS)$")
            self.assertIn("${" + key + ":-", overlay, key)

    def test_the_profile_contains_no_secret_shaped_values(self):
        for key, value in parse_env(SMALL).items():
            self.assertRegex(value, r"^[0-9.]+[mg]?$", key)


if __name__ == "__main__":
    unittest.main()
