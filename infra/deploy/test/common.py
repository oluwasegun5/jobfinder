"""Helpers shared by the deployment tests. Standard library only."""
from __future__ import annotations

import secrets
import shutil
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
INFRA = REPO / "infra"
DEPLOY = INFRA / "deploy"


def fixture_env(passphrase_file: str = "", **overrides: str) -> dict[str, str]:
    """A complete, valid production environment built at runtime from random fragments (no secret-shaped literals)."""
    def hexs(n: int) -> str:
        return secrets.token_hex(n)

    env = {
        "SITE_ADDRESS": "app.jobfinder-test.invalid",
        "WEB_BASE_URL": "https://app.jobfinder-test.invalid",
        "IMAGE_REGISTRY": "registry.invalid/jobfinder",
        "IMAGE_TAG": "v0.0.1",
        "POSTGRES_PASSWORD": hexs(16),
        "RABBITMQ_PASSWORD": hexs(16),
        "REDIS_PASSWORD": hexs(16),
        "JWT_SECRET": hexs(32),
        "AI_SERVICE_TOKEN": hexs(32),
        "NOTIFICATIONS_UNSUBSCRIBE_SECRET": hexs(32),
        "OBJECT_STORAGE_ENDPOINT": "https://storage.jobfinder-test.invalid",
        "OBJECT_STORAGE_BUCKET": "jobfinder",
        "OBJECT_STORAGE_ACCESS_KEY": hexs(8),
        "OBJECT_STORAGE_SECRET_KEY": hexs(16),
        "MAIL_HOST": "smtp.jobfinder-test.invalid",
        "MAIL_PORT": "587",
        "LLM_PROVIDER": "anthropic",
        "ANTHROPIC_API_KEY": hexs(12),
        "EMBEDDING_PROVIDER": "voyage",
        "VOYAGE_API_KEY": hexs(12),
        "CORE_API_SENTRY_DSN": "https://" + hexs(4) + "@sentry.invalid/1",
        "BACKUP_RETENTION_DAYS": "30",
    }
    if passphrase_file:
        env["BACKUP_PASSPHRASE_HOST_FILE"] = passphrase_file
        env["BACKUP_PASSPHRASE_FILE"] = "/run/secrets/backup_passphrase"
    env.update(overrides)
    return env


def write_env(path: Path, env: dict[str, str], mode: int = 0o600) -> Path:
    path.write_text("".join(f"{k}={v}\n" for k, v in env.items()))
    path.chmod(mode)
    return path


def docker_available() -> bool:
    if shutil.which("docker") is None:
        return False
    return subprocess.run(["docker", "compose", "version"], capture_output=True).returncode == 0


def compose_config(env_file: Path, *extra_files: Path) -> dict:
    """`docker compose config --format json` of the base file plus the production overlay (plus extras)."""
    import json

    cmd = ["docker", "compose", "-p", "jobfinder-test", "-f", str(INFRA / "docker-compose.yml"), "-f", str(INFRA / "docker-compose.prod.yml")]
    for f in extra_files:
        cmd += ["-f", str(f)]
    cmd += ["--env-file", str(env_file), "config", "--format", "json"]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=REPO)
    if result.returncode != 0:
        raise AssertionError(f"docker compose config failed:\n{result.stderr}")
    return json.loads(result.stdout)
