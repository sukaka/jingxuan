from fastapi.testclient import TestClient

from app.main import create_app

client = TestClient(create_app())


def test_health_ok():
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "ai-worker"
    assert body["version"] == "0.1.0"
    assert resp.headers["X-Request-Id"]
