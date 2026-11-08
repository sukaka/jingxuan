from fastapi.testclient import TestClient

from app.main import create_app

client = TestClient(create_app())
HEADERS = {"X-AI-Internal-Token": "test-internal-token"}


def payload() -> dict:
    return {
        "question": "知识库连接测试",
        "end_user_id": "crm-config-test",
        "provider_config": {"provider": "mock", "timeout_seconds": 30},
    }


def test_mock_knowledge_chat_uses_internal_contract_and_request_id():
    resp = client.post(
        "/api/v1/knowledge/chat", json=payload(),
        headers={**HEADERS, "X-Request-Id": "knowledge-req-1"},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["requestId"] == "knowledge-req-1"
    assert body["provider"] == "mock"
    assert "模拟" in body["answer"]
    assert body["citations"] == []
    assert resp.headers["X-Request-Id"] == "knowledge-req-1"


def test_knowledge_chat_requires_internal_token():
    resp = client.post("/api/v1/knowledge/chat", json=payload())

    assert resp.status_code == 401
    assert resp.json()["code"] == "UNAUTHORIZED"


def test_knowledge_chat_rejects_extra_fields_blank_and_too_long_questions():
    extra = {**payload(), "tenant_id": 2, "dataset_id": "untrusted"}
    resp = client.post("/api/v1/knowledge/chat", json=extra, headers=HEADERS)
    assert resp.status_code == 422
    assert resp.json()["code"] == "VALIDATION_FAILED"

    for question in (" ", "问" * 2001):
        body = {**payload(), "question": question}
        resp = client.post("/api/v1/knowledge/chat", json=body, headers=HEADERS)
        assert resp.status_code == 422


def test_disabled_knowledge_provider_does_not_fall_back_to_mock():
    body = payload()
    body["provider_config"] = {"provider": "disabled"}
    resp = client.post("/api/v1/knowledge/chat", json=body, headers=HEADERS)

    assert resp.status_code == 503
    assert resp.json()["code"] == "AI_PROVIDER_DISABLED"
