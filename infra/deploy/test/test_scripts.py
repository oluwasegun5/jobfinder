"""Tests of the shell scripts that need no Docker: pruning, the freshness check, preflight, the Caddyfile and syntax.
Everything runs in temporary directories with synthetic files."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

from common import DEPLOY, fixture_env, write_env

BACKUP = DEPLOY / "backup"
BASH = shutil.which("bash")


def run(script: Path, *args: str, env: dict | None = None) -> subprocess.CompletedProcess:
    full_env = {"PATH": os.environ["PATH"], "HOME": os.environ.get("HOME", "/tmp"), **(env or {})}
    return subprocess.run([BASH, str(script), *args], capture_output=True, text=True, env=full_env)


def make_backup(directory: Path, name: str, age_days: float) -> Path:
    path = directory / name
    path.write_bytes(b"synthetic")
    stamp = time.time() - age_days * 86400
    os.utime(path, (stamp, stamp))
    return path


class Prune(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def prune(self, days="30", **extra):
        return run(BACKUP / "prune.sh", env={"BACKUP_DIR": str(self.dir), "BACKUP_RETENTION_DAYS": days, **extra})

    def test_removes_only_what_is_older_than_the_retention_period(self):
        old = make_backup(self.dir, "jobfinder-20260801T010000Z-daily.dump.enc", 31)
        old_sum = make_backup(self.dir, "jobfinder-20260801T010000Z-daily.dump.enc.sha256", 31)
        edge_in = make_backup(self.dir, "jobfinder-20260901T010000Z-daily.dump", 29.5)
        recent = make_backup(self.dir, "jobfinder-20261006T010000Z-daily.dump", 1)
        pre = make_backup(self.dir, "jobfinder-20260820T010000Z-pre-deploy.dump", 45)
        result = self.prune()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(old.exists())
        self.assertFalse(old_sum.exists(), "the checksum file goes with its backup")
        self.assertFalse(pre.exists(), "pre-deploy and manual backups obey the same period")
        self.assertTrue(edge_in.exists())
        self.assertTrue(recent.exists())

    def test_never_touches_files_that_are_not_backups(self):
        other = make_backup(self.dir, "notes.txt", 400)
        marker = make_backup(self.dir, ".last_upload_ok", 400)
        partial = make_backup(self.dir, "unrelated.dump", 400)
        self.assertEqual(self.prune().returncode, 0)
        for f in (other, marker, partial):
            self.assertTrue(f.exists(), f.name)

    def test_there_is_no_keep_the_newest_exception(self):
        only = make_backup(self.dir, "jobfinder-20250101T010000Z-daily.dump", 200)
        self.assertEqual(self.prune().returncode, 0)
        self.assertFalse(only.exists(), "the retention period is a promise to users, even if it leaves no backup")

    def test_a_shorter_period_is_honoured(self):
        three = make_backup(self.dir, "jobfinder-20261004T010000Z-daily.dump", 3)
        one = make_backup(self.dir, "jobfinder-20261006T010000Z-daily.dump", 1)
        self.assertEqual(self.prune("2").returncode, 0)
        self.assertFalse(three.exists())
        self.assertTrue(one.exists())

    def test_rejects_a_nonsense_period_without_deleting_anything(self):
        keep = make_backup(self.dir, "jobfinder-20250101T010000Z-daily.dump", 400)
        for bad in ("0", "-5", "abc", "3.5"):
            result = self.prune(bad)
            self.assertNotEqual(result.returncode, 0, bad)
            self.assertTrue(keep.exists(), bad)

    def test_a_missing_directory_is_not_an_error(self):
        result = run(BACKUP / "prune.sh", env={"BACKUP_DIR": str(self.dir / "absent"), "BACKUP_RETENTION_DAYS": "30"})
        self.assertEqual(result.returncode, 0, result.stderr)


class Status(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def status(self, **extra):
        return run(BACKUP / "backup-status.sh", env={"BACKUP_DIR": str(self.dir), "BACKUP_MAX_AGE_HOURS": "30", **extra})

    def test_fails_without_any_backup(self):
        self.assertEqual(self.status().returncode, 1)

    def test_passes_with_a_fresh_daily_backup(self):
        make_backup(self.dir, "jobfinder-20261007T010000Z-daily.dump", 0.2)
        self.assertEqual(self.status().returncode, 0)

    def test_fails_when_the_newest_daily_backup_is_too_old(self):
        make_backup(self.dir, "jobfinder-20261001T010000Z-daily.dump", 6)
        self.assertEqual(self.status().returncode, 1)

    def test_pre_deploy_backups_do_not_count_as_the_daily_one(self):
        make_backup(self.dir, "jobfinder-20261007T010000Z-pre-deploy.dump", 0.1)
        self.assertEqual(self.status().returncode, 1)

    def test_a_checksum_file_alone_does_not_count(self):
        make_backup(self.dir, "jobfinder-20261007T010000Z-daily.dump.sha256", 0.1)
        self.assertEqual(self.status().returncode, 1)

    def test_with_an_offsite_copy_configured_a_stale_upload_marker_fails(self):
        make_backup(self.dir, "jobfinder-20261007T010000Z-daily.dump", 0.1)
        s3 = {"BACKUP_S3_URI": "s3://bucket/prefix"}
        self.assertEqual(self.status(**s3).returncode, 1)
        make_backup(self.dir, ".last_upload_ok", 3)
        self.assertEqual(self.status(**s3).returncode, 1)
        make_backup(self.dir, ".last_upload_ok", 0.1)
        self.assertEqual(self.status(**s3).returncode, 0)


class Preflight(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.passphrase = self.dir / "passphrase"
        self.passphrase.write_text("p" * 40)

    def tearDown(self):
        self.tmp.cleanup()

    def good(self, **overrides):
        return fixture_env(passphrase_file=str(self.passphrase), **overrides)

    def check(self, env: dict, mode: int = 0o600, *flags: str):
        f = write_env(self.dir / "env", env, mode)
        return run(DEPLOY / "preflight.sh", str(f), *flags)

    def test_a_complete_production_file_passes(self):
        result = self.check(self.good())
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("0 error(s)", result.stdout)

    def test_every_missing_or_placeholder_secret_is_refused(self):
        for key in ("POSTGRES_PASSWORD", "RABBITMQ_PASSWORD", "REDIS_PASSWORD", "JWT_SECRET", "AI_SERVICE_TOKEN",
                    "NOTIFICATIONS_UNSUBSCRIBE_SECRET", "OBJECT_STORAGE_ACCESS_KEY", "OBJECT_STORAGE_SECRET_KEY", "MAIL_HOST"):
            env = self.good()
            env[key] = "change-me-" + key.lower()
            result = self.check(env)
            self.assertEqual(result.returncode, 1, f"{key} placeholder accepted")
            self.assertIn(key, result.stdout)
            env = self.good()
            del env[key]
            self.assertEqual(self.check(env).returncode, 1, f"missing {key} accepted")

    def test_short_secrets_are_refused(self):
        self.assertEqual(self.check(self.good(JWT_SECRET="short")).returncode, 1)
        self.assertEqual(self.check(self.good(REDIS_PASSWORD="abc123")).returncode, 1)

    def test_redis_password_must_be_url_safe(self):
        result = self.check(self.good(REDIS_PASSWORD="a" * 20 + "/@:" + "b" * 20))
        self.assertEqual(result.returncode, 1)
        self.assertIn("REDIS_PASSWORD", result.stdout)

    def test_the_public_url_must_match_the_site_address_and_be_https(self):
        self.assertEqual(self.check(self.good(WEB_BASE_URL="http://app.jobfinder-test.invalid")).returncode, 1)
        self.assertEqual(self.check(self.good(WEB_BASE_URL="https://elsewhere.jobfinder-test.invalid")).returncode, 1)
        self.assertEqual(self.check(self.good(SITE_ADDRESS="localhost", WEB_BASE_URL="https://localhost")).returncode, 1)

    def test_local_tls_is_refused_in_production_only(self):
        self.assertEqual(self.check(self.good(CADDY_TLS="tls internal")).returncode, 1)

    def test_unsafe_switches_are_refused(self):
        self.assertEqual(self.check(self.good(LLM_PROVIDER="fake")).returncode, 1)
        self.assertEqual(self.check(self.good(AUTH_COOKIE_SECURE="false")).returncode, 1)
        self.assertEqual(self.check(self.good(UPLOAD_SCANNER_TYPE="none")).returncode, 1)
        self.assertEqual(self.check(self.good(UPLOAD_SCANNER_TYPE="none", ALLOW_NO_UPLOAD_SCAN="true")).returncode, 0)

    def test_a_payment_provider_with_a_key_needs_real_prices(self):
        key = "k" * 24
        self.assertEqual(self.check(self.good(STRIPE_SECRET_KEY=key)).returncode, 1)
        ok = self.good(STRIPE_SECRET_KEY=key, STRIPE_WEBHOOK_SECRET="w" * 24, STRIPE_PRICE_PRO_USD="price_" + "a" * 14,
                         BILLING_PRICE_PRO_USD_MINOR="900", BILLING_PRICE_PACK_SMALL_USD_MINOR="500", BILLING_PRICE_PACK_LARGE_USD_MINOR="2000")
        self.assertEqual(self.check(ok).returncode, 0, self.check(ok).stdout)
        ok["BILLING_PRICE_PACK_LARGE_USD_MINOR"] = "100"
        self.assertEqual(self.check(ok).returncode, 1, "the placeholder price 100 must be refused")

    def test_offsite_backups_must_be_encrypted(self):
        s3 = dict(BACKUP_S3_URI="s3://bucket/b", BACKUP_S3_ENDPOINT="https://s3.invalid", BACKUP_S3_ACCESS_KEY="a" * 16, BACKUP_S3_SECRET_KEY="s" * 24)
        result = self.check(fixture_env(**s3))
        self.assertEqual(result.returncode, 1)
        self.assertIn("encrypted", result.stdout)
        ok = self.good(**s3)
        self.assertEqual(self.check(ok).returncode, 0, self.check(ok).stdout)

    def test_production_backups_need_the_passphrase_file(self):
        result = self.check(fixture_env())
        self.assertEqual(result.returncode, 1)
        self.assertIn("BACKUP_PASSPHRASE_HOST_FILE is required", result.stdout)
        missing = self.good(BACKUP_PASSPHRASE_HOST_FILE=str(self.dir / "absent"))
        self.assertEqual(self.check(missing).returncode, 1)
        wrong_mount = self.good(BACKUP_PASSPHRASE_FILE="/somewhere/else")
        self.assertEqual(self.check(wrong_mount).returncode, 1)

    def test_a_world_readable_env_file_is_refused(self):
        self.assertEqual(self.check(self.good(), 0o644).returncode, 1)
        self.assertEqual(self.check(self.good(), 0o604).returncode, 1)

    def test_retention_must_be_a_positive_whole_number(self):
        for bad in ("0", "thirty", "-1", "1.5"):
            self.assertEqual(self.check(self.good(BACKUP_RETENTION_DAYS=bad)).returncode, 1, bad)

    def test_rehearsal_mode_accepts_local_values_but_not_missing_secrets(self):
        local = self.good(SITE_ADDRESS="localhost", WEB_BASE_URL="https://localhost:18443", CADDY_TLS="tls internal", LLM_PROVIDER="fake",
                            UPLOAD_SCANNER_TYPE="none")
        self.assertEqual(self.check(local, 0o600, "--rehearsal").returncode, 0)
        del local["JWT_SECRET"]
        self.assertEqual(self.check(local, 0o600, "--rehearsal").returncode, 1)


class ScriptHygiene(unittest.TestCase):
    scripts = sorted(list(DEPLOY.glob("*.sh")) + list(BACKUP.glob("*.sh")) + list((DEPLOY / "loadtest").glob("*.sh")))

    def test_scripts_exist_and_are_executable(self):
        self.assertGreaterEqual(len(self.scripts), 12)
        for s in self.scripts:
            self.assertTrue(os.access(s, os.X_OK), f"{s.name} is not executable")

    def test_bash_syntax(self):
        for s in self.scripts:
            result = subprocess.run([BASH, "-n", str(s)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, f"{s.name}: {result.stderr}")

    @unittest.skipUnless(shutil.which("shellcheck"), "shellcheck is not installed")
    def test_shellcheck(self):
        result = subprocess.run(["shellcheck", "--severity=warning", "-x", *map(str, self.scripts)], capture_output=True, text=True, cwd=DEPLOY)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_no_script_deploys_anywhere_by_itself(self):
        # The only network destinations in the scripts are the stack's own URL, the mail API of the rehearsal and the
        # container registry named in the env file: no hard-coded cloud, registry or domain.
        import re

        for s in self.scripts:
            for line in s.read_text().splitlines():
                if line.strip().startswith("#"):
                    continue
                for url in re.findall(r"https?://[A-Za-z0-9._-]+", line):
                    self.assertTrue(
                        url.split("//")[1] in {"localhost", "127.0.0.1", "object-storage", "mailpit", "app", "host"} or "$" in line,
                        f"{s.name}: unexpected host in: {line.strip()}",
                    )


class SmokeScript(unittest.TestCase):
    def test_refuses_an_http_base_url(self):
        result = run(DEPLOY / "smoke.sh", env={"SMOKE_BASE_URL": "http://localhost"})
        self.assertEqual(result.returncode, 64)

    def test_requires_a_base_url(self):
        result = run(DEPLOY / "smoke.sh")
        self.assertNotEqual(result.returncode, 0)

    @unittest.skipUnless(shutil.which("curl"), "curl is needed")
    def test_an_unreachable_stack_fails_instead_of_passing(self):
        result = run(DEPLOY / "smoke.sh", env={"SMOKE_BASE_URL": "https://127.0.0.1:9", "SMOKE_INSECURE": "1"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL", result.stdout)
        self.assertNotIn("PASS  GET /", result.stdout)


class DeployScripts(unittest.TestCase):
    def test_deploy_refuses_latest_and_bad_tags_before_doing_anything(self):
        for tag in ("latest", "bad tag", "x;rm -rf /", ""):
            result = run(DEPLOY / "deploy.sh", tag, env={"ENV_FILE": "/nonexistent"})
            self.assertNotEqual(result.returncode, 0, tag)
            self.assertNotIn("starting the stack", result.stdout + result.stderr)

    def test_rollback_needs_a_recorded_or_given_tag(self):
        with tempfile.TemporaryDirectory() as d:
            result = run(DEPLOY / "rollback.sh", env={"STATE_DIR": d, "ENV_FILE": "/nonexistent"})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("no previous tag", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
