"""Tests of the S3-compatible off-host backup settings added for Oracle Cloud Object Storage (path-style addressing,
ListObjects fallback). A fake `aws` command on PATH records what it was called with; nothing touches a network."""
from __future__ import annotations

import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from common import DEPLOY, compose_config, docker_available, fixture_env, write_env

BACKUP = DEPLOY / "backup"
BASH = shutil.which("bash")

FAKE_AWS = r"""#!/usr/bin/env bash
# Fake aws CLI. Logs the call; FAKE_AWS_V2=fail makes list-objects-v2 fail like a store without ListObjectsV2.
{
  echo "ARGS: $*"
  echo "REGION: ${AWS_DEFAULT_REGION:-}"
  echo "CONFIG_FILE: ${AWS_CONFIG_FILE:-}"
  if [ -n "${AWS_CONFIG_FILE:-}" ] && [ -r "$AWS_CONFIG_FILE" ]; then sed 's/^/CONFIG| /' "$AWS_CONFIG_FILE"; fi
} >> "$FAKE_AWS_LOG"
case "$*" in
  *list-objects-v2*) [ "${FAKE_AWS_V2:-ok}" = "fail" ] && exit 254; printf '%s\n' "${FAKE_AWS_KEYS:-None}" ;;
  *list-objects*) printf '%s\n' "${FAKE_AWS_KEYS:-None}" ;;
esac
exit 0
"""

# prune.sh runs in the Debian image (GNU date). A stand-in for `date -u -d "now - N minutes" +FORMAT` keeps these tests
# runnable on macOS (BSD date); everything else is passed to the real date.
FAKE_DATE = r"""#!/usr/bin/env bash
if [ "${1:-}" = "-u" ] && [ "${2:-}" = "-d" ]; then
  python3 -c 'import datetime,re,sys; m=re.fullmatch(r"now - (\d+) minutes", sys.argv[1]); d=datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=int(m.group(1))); print(d.strftime(sys.argv[2][1:].replace("%T","%H:%M:%S")))' "$3" "$4"
  exit 0
fi
exec /bin/date "$@"
"""


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.bin = self.dir / "bin"
        self.bin.mkdir()
        fake = self.bin / "aws"
        fake.write_text(FAKE_AWS)
        fake.chmod(fake.stat().st_mode | stat.S_IXUSR)
        fake_date = self.bin / "date"
        fake_date.write_text(FAKE_DATE)
        fake_date.chmod(fake_date.stat().st_mode | stat.S_IXUSR)
        self.log = self.dir / "aws.log"
        self.work = self.dir / "tmpdir"
        self.work.mkdir()
        self.base_config = self.dir / "base-config"
        self.base_config.write_text("[plugins]\ncli_legacy_plugin_path = /x\n")

    def tearDown(self):
        self.tmp.cleanup()

    def env(self, **extra):
        env = {
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "HOME": str(self.dir),
            "TMPDIR": str(self.work),
            "FAKE_AWS_LOG": str(self.log),
            "AWS_CONFIG_FILE": str(self.base_config),
            "BACKUP_DIR": str(self.dir / "backups"),
            "BACKUP_RETENTION_DAYS": "30",
            "BACKUP_S3_URI": "s3://bucket/prefix",
            "BACKUP_S3_ENDPOINT": "https://ns.compat.objectstorage.eu-frankfurt-1.oci.customer-oci.com",
            "BACKUP_S3_ACCESS_KEY": "ak" + "1" * 6,
            "BACKUP_S3_SECRET_KEY": "sk" + "2" * 6,
            "BACKUP_S3_REGION": "eu-frankfurt-1",
        }
        env.update(extra)
        return env

    def sh(self, script: str, **extra) -> subprocess.CompletedProcess:
        return subprocess.run([BASH, "-c", script], capture_output=True, text=True, env=self.env(**extra))

    def logged(self) -> str:
        return self.log.read_text() if self.log.exists() else ""


class AddressingStyle(Base):
    call = f'. "{BACKUP}/backup-lib.sh"; aws_s3 s3 ls s3://bucket/'

    def test_path_style_is_written_to_a_private_copy_of_the_config(self):
        result = self.sh(self.call, BACKUP_S3_ADDRESSING_STYLE="path")
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.logged()
        self.assertIn("addressing_style = path", log)
        self.assertIn("[plugins]", log)  # the image's own settings (the no-Expect plugin) are kept
        self.assertIn("--endpoint-url https://ns.compat.objectstorage.eu-frankfurt-1.oci.customer-oci.com", log)
        self.assertIn("REGION: eu-frankfurt-1", log)

    def test_the_base_config_is_never_modified_and_the_copy_is_removed(self):
        before = self.base_config.read_text()
        self.sh(self.call, BACKUP_S3_ADDRESSING_STYLE="path")
        self.assertEqual(self.base_config.read_text(), before)
        self.assertEqual(list(self.work.iterdir()), [], "the temporary config file was left behind")

    def test_without_a_style_the_image_config_is_used_unchanged(self):
        result = self.sh(self.call)
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.logged()
        self.assertIn(f"CONFIG_FILE: {self.base_config}", log)
        self.assertNotIn("addressing_style", log)

    def test_virtual_and_auto_are_accepted(self):
        for style in ("virtual", "auto"):
            self.log.unlink(missing_ok=True)
            self.assertEqual(self.sh(self.call, BACKUP_S3_ADDRESSING_STYLE=style).returncode, 0)
            self.assertIn(f"addressing_style = {style}", self.logged())

    def test_a_bad_style_is_refused_before_aws_runs(self):
        result = self.sh(self.call, BACKUP_S3_ADDRESSING_STYLE="bucket-first")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("BACKUP_S3_ADDRESSING_STYLE", result.stderr)
        self.assertEqual(self.logged(), "")

    def test_the_exit_status_of_aws_is_passed_on(self):
        failing = self.bin / "aws"
        failing.write_text("#!/usr/bin/env bash\nexit 7\n")
        result = self.sh(self.call + "; echo rc=$?", BACKUP_S3_ADDRESSING_STYLE="path")
        self.assertIn("rc=7", result.stdout)
        self.assertEqual(list(self.work.iterdir()), [])

    def test_credentials_are_required_with_an_off_host_uri(self):
        result = self.sh(self.call, BACKUP_S3_ACCESS_KEY="")
        self.assertNotEqual(result.returncode, 0)


class PruneOffHost(Base):
    prune = str(BACKUP / "prune.sh")

    def run_prune(self, **extra):
        (self.dir / "backups").mkdir(exist_ok=True)
        return subprocess.run([BASH, self.prune], capture_output=True, text=True,
                              env=self.env(PRUNE_AGE_MINUTES_OVERRIDE="0", **extra))

    def deleted(self) -> list[str]:
        return [ln for ln in self.logged().splitlines() if "delete-object" in ln]

    def test_old_backups_are_deleted_with_listobjects_v2(self):
        result = self.run_prune(FAKE_AWS_KEYS="prefix/jobfinder-1.dump.enc\tprefix/jobfinder-1.dump.enc.sha256")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.deleted()), 2)
        self.assertNotIn("s3api list-objects --", self.logged())

    def test_a_store_without_listobjects_v2_falls_back_to_listobjects(self):
        result = self.run_prune(FAKE_AWS_V2="fail", FAKE_AWS_KEYS="prefix/jobfinder-9.dump")
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.logged()
        self.assertIn("s3api list-objects-v2", log)
        self.assertIn("s3api list-objects --bucket", log)
        self.assertEqual(len(self.deleted()), 1)
        self.assertIn("--key prefix/jobfinder-9.dump", self.deleted()[0])

    def test_keys_that_are_not_backups_are_never_deleted(self):
        self.run_prune(FAKE_AWS_KEYS="prefix/jobfinder-notes.txt\tprefix/other.dump")
        self.assertEqual(self.deleted(), [])

    def test_nothing_to_delete_is_not_an_error(self):
        result = self.run_prune(FAKE_AWS_KEYS="None")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.deleted(), [])

    def test_path_style_applies_to_the_off_host_calls_too(self):
        self.run_prune(BACKUP_S3_ADDRESSING_STYLE="path", FAKE_AWS_KEYS="prefix/jobfinder-1.dump")
        self.assertIn("addressing_style = path", self.logged())


class PreflightAndCompose(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.passphrase = self.dir / "passphrase"
        self.passphrase.write_text("p" * 40)

    def tearDown(self):
        self.tmp.cleanup()

    def preflight(self, **extra):
        s3 = dict(BACKUP_S3_URI="s3://bucket/b", BACKUP_S3_ENDPOINT="https://s3.invalid",
                  BACKUP_S3_ACCESS_KEY="a" * 16, BACKUP_S3_SECRET_KEY="s" * 24)
        env = fixture_env(passphrase_file=str(self.passphrase), **s3, **extra)
        f = write_env(self.dir / "env", env)
        full = {"PATH": os.environ["PATH"], "HOME": str(self.dir)}
        return subprocess.run([BASH, str(DEPLOY / "preflight.sh"), str(f)], capture_output=True, text=True, env=full)

    def test_preflight_accepts_the_three_styles(self):
        for style in ("path", "virtual", "auto", ""):
            result = self.preflight(BACKUP_S3_ADDRESSING_STYLE=style)
            self.assertEqual(result.returncode, 0, f"{style!r}: {result.stdout}")

    def test_preflight_refuses_an_unknown_style(self):
        result = self.preflight(BACKUP_S3_ADDRESSING_STYLE="vhost")
        self.assertEqual(result.returncode, 1)
        self.assertIn("BACKUP_S3_ADDRESSING_STYLE", result.stdout)

    def test_the_example_env_documents_the_setting(self):
        self.assertIn("BACKUP_S3_ADDRESSING_STYLE=", (DEPLOY / "env.production.example").read_text())

    @unittest.skipUnless(docker_available(), "docker compose is not available")
    def test_the_backup_service_receives_the_setting(self):
        env = fixture_env(BACKUP_S3_ADDRESSING_STYLE="path")
        cfg = compose_config(write_env(self.dir / "prod.env", env))
        self.assertEqual(cfg["services"]["backup"]["environment"]["BACKUP_S3_ADDRESSING_STYLE"], "path")
        default = compose_config(write_env(self.dir / "default.env", fixture_env()))
        self.assertEqual(default["services"]["backup"]["environment"]["BACKUP_S3_ADDRESSING_STYLE"], "")


if __name__ == "__main__":
    unittest.main()
