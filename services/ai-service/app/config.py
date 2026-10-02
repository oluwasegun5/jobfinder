"""Service configuration, loaded from environment variables (secrets only from env)."""

from decimal import Decimal
from enum import StrEnum
from functools import lru_cache

from pydantic import BaseModel, Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class ProviderName(StrEnum):
    ANTHROPIC = "anthropic"
    # Deterministic, keyless stand-in for local runs and evals: it answers match_scoring from the
    # overlap between the candidate and the job (docs/adr/0026-matching-engine.md) and tailor_resume
    # by reordering only (docs/adr/0029-resume-tailoring.md); cover_letter and screening_answers
    # come from templates over the resume's own facts (docs/adr/0031). Never the default. No switch
    # that makes it misbehave: tests script bad model output with the in-memory FakeProvider.
    FAKE = "fake"


class EmbeddingProviderName(StrEnum):
    VOYAGE = "voyage"
    # Deterministic, keyless vectors for tests and local development; carries no meaning.
    FAKE = "fake"


class ModelPricing(BaseModel):
    """USD per million tokens."""

    input_per_mtok: Decimal
    output_per_mtok: Decimal


def _default_pricing() -> dict[str, ModelPricing]:
    return {
        "claude-haiku-4-5": ModelPricing(input_per_mtok=Decimal("1"), output_per_mtok=Decimal("5")),
        "claude-sonnet-5": ModelPricing(input_per_mtok=Decimal("2"), output_per_mtok=Decimal("10")),
        # Embeddings bill input tokens only (list price, checked 2026-10-01).
        "voyage-4": ModelPricing(input_per_mtok=Decimal("0.06"), output_per_mtok=Decimal("0")),
    }


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore", frozen=True)

    # Shared secret core-api sends in the X-Service-Token header.
    ai_service_token: SecretStr = Field(min_length=32)

    llm_provider: ProviderName = ProviderName.ANTHROPIC
    anthropic_api_key: SecretStr | None = None
    # Only for API keys not scoped to a workspace: sent as the anthropic-workspace-id header.
    anthropic_workspace_id: str | None = None
    # Model routing (PLAN.md §7): never hard-code model names in business logic.
    llm_model_fast: str = "claude-haiku-4-5"
    llm_model_strong: str = "claude-sonnet-5"
    llm_max_tokens: int = Field(default=4096, gt=0)
    llm_timeout_seconds: float = Field(default=60.0, gt=0)
    llm_max_retries: int = Field(default=2, ge=0)
    # JSON object in env, e.g. {"claude-haiku-4-5": {"input_per_mtok": 1, "output_per_mtok": 5}}
    llm_pricing: dict[str, ModelPricing] = Field(default_factory=_default_pricing)
    # Version label of the price list above. Bump it whenever a price changes: every usage record
    # carries it, so the ledger can tell which prices a cost was computed with.
    llm_pricing_version: str = Field(default="2026-10-01", min_length=1, max_length=40)

    # Match scoring (docs/adr/0026-matching-engine.md). One LLM call scores up to this many jobs; a
    # call's input (candidate plus jobs) stays under the character budget (about four characters per
    # token), so a request of many jobs is split into several calls. Job descriptions are cut to the
    # given length first.
    score_matches_max_jobs_per_call: int = Field(default=6, gt=0, le=50)
    score_matches_max_input_chars: int = Field(default=24000, ge=2000)
    score_matches_description_chars: int = Field(default=3000, ge=200)

    # Resume tailoring (docs/adr/0029-resume-tailoring.md). The job text is cut to this many
    # characters (after instruction-like sentences are removed) before the strong model sees it; the
    # output holds a whole resume plus notes, so it gets its own token budget.
    tailor_description_chars: int = Field(default=8000, ge=500)
    tailor_resume_max_tokens: int = Field(default=8000, gt=0)

    # Cover letters and screening answers (docs/adr/0031-cover-letters-and-application-pack.md). The
    # user's notes are cut to this many characters (after instruction-like sentences are removed);
    # each call has its own output token budget. The job text uses `tailor_description_chars`.
    writing_notes_chars: int = Field(default=1000, ge=100)
    cover_letter_max_tokens: int = Field(default=3000, gt=0)
    screening_answers_max_tokens: int = Field(default=3500, gt=0)

    # Follow-up emails (docs/adr/0032-application-tracker.md): a subject and up to four paragraphs.
    follow_up_email_max_tokens: int = Field(default=1500, gt=0)

    rabbitmq_enabled: bool = True
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = Field(default=5672, gt=0)
    rabbitmq_user: str = "guest"
    rabbitmq_password: SecretStr = SecretStr("guest")
    rabbitmq_vhost: str = "/"
    rabbitmq_prefetch: int = Field(default=64, gt=0)
    rabbitmq_reconnect_seconds: float = Field(default=5.0, gt=0)

    # Embeddings (docs/adr/0022-embeddings-pipeline.md). The model and dimension are the pinned
    # embedding space and must equal core-api's EMBEDDING_MODEL / EMBEDDING_DIMENSION; core-api
    # checks both on every request. Changing either makes core-api treat stored vectors as stale.
    embedding_provider: EmbeddingProviderName = EmbeddingProviderName.VOYAGE
    voyage_api_key: SecretStr | None = None
    voyage_base_url: str = "https://api.voyageai.com/v1"
    embedding_model: str = "voyage-4"
    embedding_dimension: int = Field(default=1024, gt=0, le=2000)
    embedding_timeout_seconds: float = Field(default=60.0, gt=0)
    # One provider call embeds up to this many queued ids; a partial batch is sent after the wait.
    embedding_batch_size: int = Field(default=32, gt=0, le=200)
    embedding_batch_wait_seconds: float = Field(default=1.0, ge=0)
    embedding_max_attempts: int = Field(default=3, gt=0)
    embedding_retry_backoff_seconds: float = Field(default=2.0, ge=0)
    jobs_embed_queue: str = "jobs.embed"
    jobs_embed_dlq: str = "jobs.embed.dlq"
    resumes_embed_queue: str = "resumes.embed"
    resumes_embed_dlq: str = "resumes.embed.dlq"
    # core-api's internal endpoints (the same service token authenticates both directions).
    core_api_base_url: str = "http://localhost:8080"
    core_api_timeout_seconds: float = Field(default=30.0, gt=0)

    log_level: str = "INFO"

    @model_validator(mode="after")
    def _fake_embeddings_are_labelled(self) -> "Settings":
        if (
            self.embedding_provider is EmbeddingProviderName.FAKE
            and not self.embedding_model.startswith("fake-")
        ):
            raise ValueError("EMBEDDING_MODEL must start with 'fake-' when EMBEDDING_PROVIDER=fake")
        return self

    @field_validator("anthropic_api_key", "voyage_api_key", "anthropic_workspace_id", mode="before")
    @classmethod
    def _blank_key_is_unset(cls, value: object) -> object:
        # Compose passes ANTHROPIC_API_KEY="" (or VOYAGE_API_KEY="") when it is not configured.
        if value is None or (isinstance(value, str) and not value.strip()):
            return None
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()
