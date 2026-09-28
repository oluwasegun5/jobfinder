from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings
from app.llm import FakeProvider
from app.main import create_app
from app.security import SERVICE_TOKEN_HEADER

TEST_TOKEN = "test-service-token-0123456789abcdef"


@pytest.fixture
def settings() -> Settings:
    return Settings(
        ai_service_token=SecretStr(TEST_TOKEN),
        rabbitmq_enabled=False,
    )


@pytest.fixture
def fake_provider() -> FakeProvider:
    return FakeProvider()


@pytest.fixture
def client(settings: Settings, fake_provider: FakeProvider) -> Iterator[TestClient]:
    app = create_app(settings, provider=fake_provider)
    with TestClient(app, headers={SERVICE_TOKEN_HEADER: TEST_TOKEN}) as c:
        yield c


@pytest.fixture
def anon_client(settings: Settings, fake_provider: FakeProvider) -> Iterator[TestClient]:
    app = create_app(settings, provider=fake_provider)
    with TestClient(app) as c:
        yield c
