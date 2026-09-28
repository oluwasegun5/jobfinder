"""Service configuration, loaded from environment variables (secrets only from env)."""

from decimal import Decimal
from enum import StrEnum
from functools import lru_cache

from pydantic import BaseModel, Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class ProviderName(StrEnum):
    ANTHROPIC = "anthropic"


class ModelPricing(BaseModel):
    """USD per million tokens."""

    input_per_mtok: Decimal
    output_per_mtok: Decimal


def _default_pricing() -> dict[str, ModelPricing]:
    return {
        "claude-haiku-4-5": ModelPricing(input_per_mtok=Decimal("1"), output_per_mtok=Decimal("5")),
        "claude-sonnet-5": ModelPricing(input_per_mtok=Decimal("2"), output_per_mtok=Decimal("10")),
    }


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore", frozen=True)

    # Shared secret core-api sends in the X-Service-Token header.
    ai_service_token: SecretStr = Field(min_length=32)

    llm_provider: ProviderName = ProviderName.ANTHROPIC
    anthropic_api_key: SecretStr | None = None
    # Model routing (PLAN.md §7): never hard-code model names in business logic.
    llm_model_fast: str = "claude-haiku-4-5"
    llm_model_strong: str = "claude-sonnet-5"
    llm_max_tokens: int = Field(default=4096, gt=0)
    llm_timeout_seconds: float = Field(default=60.0, gt=0)
    llm_max_retries: int = Field(default=2, ge=0)
    # JSON object in env, e.g. {"claude-haiku-4-5": {"input_per_mtok": 1, "output_per_mtok": 5}}
    llm_pricing: dict[str, ModelPricing] = Field(default_factory=_default_pricing)

    rabbitmq_enabled: bool = True
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = Field(default=5672, gt=0)
    rabbitmq_user: str = "guest"
    rabbitmq_password: SecretStr = SecretStr("guest")
    rabbitmq_vhost: str = "/"
    rabbitmq_prefetch: int = Field(default=10, gt=0)
    rabbitmq_reconnect_seconds: float = Field(default=5.0, gt=0)

    log_level: str = "INFO"

    @field_validator("anthropic_api_key", mode="before")
    @classmethod
    def _blank_key_is_unset(cls, value: object) -> object:
        # Compose passes ANTHROPIC_API_KEY="" when it is not configured.
        if value is None or (isinstance(value, str) and not value.strip()):
            return None
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()
