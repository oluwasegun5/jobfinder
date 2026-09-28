import pytest
from fastapi.testclient import TestClient

from app.security import SERVICE_TOKEN_HEADER


@pytest.mark.parametrize(
    ("method", "path"),
    [("GET", "/health"), ("POST", "/v1/diagnostics/llm")],
)
def test_routes_reject_missing_token(anon_client: TestClient, method: str, path: str) -> None:
    response = anon_client.request(method, path, json={})
    assert response.status_code == 401


def test_routes_reject_wrong_token(anon_client: TestClient) -> None:
    response = anon_client.get("/health", headers={SERVICE_TOKEN_HEADER: "wrong"})
    assert response.status_code == 401


def test_docs_are_not_exposed(client: TestClient) -> None:
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
