"""Static checks of the production compose stack (ADR 0041). Needs `docker compose` (config only, nothing is started)."""
from __future__ import annotations

import re
import tempfile
import unittest
from pathlib import Path

from common import DEPLOY, INFRA, REPO, compose_config, docker_available, fixture_env, write_env

APP_SERVICES = {"core-api", "ai-service", "web"}
DATA_SERVICES = {"postgres", "redis", "rabbitmq"}


@unittest.skipUnless(docker_available(), "docker compose is not available")
class ProductionStack(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.env = fixture_env()
        cls.env_file = write_env(Path(cls.tmp.name) / "prod.env", cls.env)
        cfg = compose_config(cls.env_file)
        cls.cfg = cfg
        # Services started by a plain `up` (profiles dev-only and observability stay off).
        cls.services = {n: s for n, s in cfg["services"].items() if not s.get("profiles")}

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_expected_services_run(self):
        self.assertEqual(
            set(self.services),
            {"caddy", "postgres", "redis", "rabbitmq", "clamav", "core-api", "ai-service", "web", "backup"},
        )

    def test_only_the_proxy_publishes_ports(self):
        for name, svc in self.services.items():
            if name == "caddy":
                published = {int(p["target"]) for p in svc["ports"]}
                self.assertEqual(published, {80, 443})
            else:
                self.assertFalse(svc.get("ports"), f"{name} publishes ports")

    def test_data_stores_are_on_the_internal_network_only(self):
        self.assertTrue(self.cfg["networks"]["data"].get("internal"), "the data network must be internal (no egress)")
        for name in DATA_SERVICES:
            self.assertEqual(set(self.services[name]["networks"]), {"data"}, name)
        self.assertNotIn("data", self.services["web"]["networks"])
        self.assertNotIn("data", self.services["caddy"]["networks"])
        self.assertEqual(set(self.services["caddy"]["networks"]), {"edge"})
        self.assertNotIn("edge", self.services["core-api"]["networks"])

    def test_hardening_on_every_service(self):
        for name, svc in self.services.items():
            self.assertIn("no-new-privileges:true", svc.get("security_opt", []), name)
            self.assertEqual(svc.get("restart"), "unless-stopped", name)
            self.assertTrue(svc.get("mem_limit"), f"{name} has no memory limit")
            self.assertTrue(svc.get("cpus"), f"{name} has no cpu limit")
            self.assertEqual(svc.get("logging", {}).get("options", {}).get("max-size"), "10m", f"{name} logs are not rotated")
        for name in APP_SERVICES | {"backup", "caddy"}:
            svc = self.services[name]
            self.assertTrue(svc.get("read_only"), f"{name} root filesystem is writable")
            self.assertEqual(svc.get("cap_drop"), ["ALL"], name)

    def test_every_service_can_report_health(self):
        # Compose's `--wait` and the deploy script rely on it. The three application images define HEALTHCHECK in
        # their Dockerfile (checked below); the rest declare it here.
        for name in DATA_SERVICES | {"caddy", "core-api"}:
            self.assertIn("healthcheck", self.services[name], name)

    def test_images_are_pinned(self):
        for name, svc in self.services.items():
            image = svc["image"]
            self.assertNotRegex(image, r":latest$", name)
            if name in APP_SERVICES | {"backup"}:
                self.assertEqual(image, f"{self.env['IMAGE_REGISTRY']}/{name}:{self.env['IMAGE_TAG']}", name)
            else:
                self.assertTrue(
                    "@sha256:" in image or re.search(r":\d+\.\d+\.\d+", image),
                    f"{name}: {image} is neither digest- nor exact-version-pinned",
                )

    def test_secrets_are_not_hard_coded_in_the_compose_files(self):
        text = (INFRA / "docker-compose.prod.yml").read_text()
        for line in text.splitlines():
            match = re.match(r"^\s+([A-Z0-9_]*(PASSWORD|SECRET|TOKEN|API_KEY|ACCESS_KEY)[A-Z0-9_]*):\s*(.*)$", line)
            if match:
                value = match.group(3).strip()
                self.assertTrue(
                    not value or value.startswith("${") or value.startswith("redis://:${"),
                    f"literal secret-like value: {line.strip()}",
                )

    def test_core_api_runs_the_prod_profile_with_tls_cookie_and_redis_password(self):
        env = self.services["core-api"]["environment"]
        self.assertEqual(env["SPRING_PROFILES_ACTIVE"], "prod")
        self.assertEqual(env["AUTH_COOKIE_SECURE"], "true")
        self.assertEqual(env["APP_RATELIMIT_REDISURI"], f"redis://:{self.env['REDIS_PASSWORD']}@redis:6379")
        self.assertEqual(env["APP_SECURITY_CORS_ALLOWED_ORIGINS"], self.env["WEB_BASE_URL"])
        self.assertEqual(env["UPLOAD_SCANNER_TYPE"], "clamav")
        self.assertEqual(env["UPLOAD_SCANNER_ON_ERROR"], "closed")

    def test_backup_defaults_match_the_policy(self):
        env = self.services["backup"]["environment"]
        self.assertEqual(env["BACKUP_RETENTION_DAYS"], "30")
        self.assertEqual(env["PGHOST"], "postgres")

    def test_a_missing_required_value_stops_compose(self):
        for key in ("SITE_ADDRESS", "REDIS_PASSWORD", "WEB_BASE_URL", "MAIL_HOST", "OBJECT_STORAGE_ENDPOINT", "POSTGRES_PASSWORD"):
            env = fixture_env()
            del env[key]
            f = write_env(Path(self.tmp.name) / f"missing-{key}.env", env)
            with self.assertRaises(AssertionError, msg=key):
                compose_config(f)


class ProductionFilesStatic(unittest.TestCase):
    def test_every_required_variable_is_documented_in_the_example(self):
        overlay = (INFRA / "docker-compose.prod.yml").read_text()
        required = set(re.findall(r"\$\{([A-Z0-9_]+):\?", overlay))
        example = (DEPLOY / "env.production.example").read_text()
        documented = set(re.findall(r"^#?\s*([A-Z0-9_]+)=", example, flags=re.M))
        self.assertFalse(required - documented, f"required but not in env.production.example: {sorted(required - documented)}")

    def test_the_example_only_holds_placeholders(self):
        example = (DEPLOY / "env.production.example").read_text()
        for line in example.splitlines():
            if re.match(r"^[A-Z0-9_]*(PASSWORD|SECRET|TOKEN|KEY)[A-Z0-9_]*=.+", line):
                self.assertRegex(line, r"=(change-me|jobfinder$|auto$|\s*$)", line)

    def test_dockerfiles_pin_base_images_and_run_unprivileged(self):
        for dockerfile in sorted((INFRA / "docker").glob("*.Dockerfile")):
            text = dockerfile.read_text()
            froms = re.findall(r"^FROM\s+(\S+)", text, flags=re.M)
            self.assertTrue(froms, dockerfile.name)
            for image in froms:
                if image in {"build"}:
                    continue
                self.assertIn("@sha256:", image, f"{dockerfile.name}: {image} is not digest-pinned")
            users = re.findall(r"^USER\s+(\S+)", text, flags=re.M)
            self.assertTrue(users, f"{dockerfile.name} never sets USER")
            self.assertNotIn(users[-1], {"root", "0"}, dockerfile.name)

    def test_web_runtime_image_ships_without_the_bundled_package_managers(self):
        text = (INFRA / "docker" / "web.Dockerfile").read_text()
        runtime = text.split("AS build", 1)[1].split("\nFROM ", 1)[1]
        self.assertRegex(runtime, r"rm -rf [^\n]*/usr/local/lib/node_modules/npm")
        self.assertLess(runtime.index("rm -rf"), runtime.index("USER node"))

    def test_backup_retention_matches_the_privacy_policy_constant(self):
        placeholders = (REPO / "apps/web/src/features/legal/placeholders.ts").read_text()
        policy_days = re.search(r"backups:\s*(\d+)", placeholders)
        self.assertIsNotNone(policy_days, "RETENTION_DAYS.backups is missing in placeholders.ts")
        lib = (DEPLOY / "backup/backup-lib.sh").read_text()
        self.assertEqual(re.search(r":\s*\"\$\{BACKUP_RETENTION_DAYS:=(\d+)\}\"", lib).group(1), policy_days.group(1))
        overlay = (INFRA / "docker-compose.prod.yml").read_text()
        self.assertEqual(re.search(r"BACKUP_RETENTION_DAYS: \$\{BACKUP_RETENTION_DAYS:-(\d+)\}", overlay).group(1), policy_days.group(1))
        example = (DEPLOY / "env.production.example").read_text()
        self.assertEqual(re.search(r"^BACKUP_RETENTION_DAYS=(\d+)", example, flags=re.M).group(1), policy_days.group(1))
        inventory = (REPO / "docs/compliance/data-inventory.md").read_text()
        self.assertTrue(re.search(rf"Database backups \| {policy_days.group(1)} days", inventory), "data-inventory.md states another backup period")

    def test_no_backup_placeholder_is_left_in_the_compliance_texts(self):
        for path in ("docs/compliance/data-inventory.md", "docs/compliance/dpa-template.md", "apps/web/src/features/legal/privacy-policy.tsx"):
            self.assertNotIn("BACKUP RETENTION PERIOD", (REPO / path).read_text(), path)

    def test_caddyfile_is_wired_to_the_web_container_only(self):
        caddyfile = (DEPLOY / "Caddyfile").read_text()
        self.assertIn("reverse_proxy web:3000", caddyfile)
        code = "\n".join(line for line in caddyfile.splitlines() if not line.strip().startswith("#"))
        self.assertNotIn("core-api", code)
        self.assertNotIn("tls internal", code)  # comes from CADDY_TLS in the rehearsal only


if __name__ == "__main__":
    unittest.main()
