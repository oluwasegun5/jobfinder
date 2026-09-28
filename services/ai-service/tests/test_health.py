from fastapi.testclient import TestClient


def test_health_is_up_with_consumer_disabled(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "rabbitmq": "DISABLED"}
