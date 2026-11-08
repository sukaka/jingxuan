from fastapi.testclient import TestClient

from app.main import create_app

client = TestClient(create_app())
HEADERS = {"X-AI-Internal-Token": "test-internal-token"}


def sync_payload() -> dict:
    return {
        "job_id": "job-api-1",
        "name": "合成知识.md",
        "text": "# 合成知识\n仅用于测试。",
        "metadata": {
            "tenant_id": "1",
            "material_id": "10",
            "revision_id": "20",
            "revision_no": 2,
            "category_code": "GUIDE",
            "institution_id": None,
            "project_id": None,
            "visibility": "STAFF_INTERNAL",
            "content_hash": "b" * 64,
        },
        "dataset_config": {"provider": "mock", "dataset_id": "mock-dataset", "timeout_seconds": 30},
    }


def test_mock_dataset_sync_status_and_disable_use_internal_contract():
    sync = client.post("/api/v1/knowledge/documents/sync", json=sync_payload(),
                       headers={**HEADERS, "X-Request-Id": "dataset-req-1"})
    assert sync.status_code == 200
    created = sync.json()
    assert created["requestId"] == "dataset-req-1"
    assert created["provider"] == "mock"
    assert created["indexing_status"] == "AVAILABLE"
    assert created["document_id"] == "mock-document-job-api-1"

    status_body = {
        "batch": created["batch"],
        "document_id": created["document_id"],
        "dataset_config": {"provider": "mock", "dataset_id": "mock-dataset"},
    }
    status = client.post("/api/v1/knowledge/documents/status", json=status_body, headers=HEADERS)
    assert status.status_code == 200
    assert status.json()["indexing_status"] == "AVAILABLE"

    disable = client.post("/api/v1/knowledge/documents/disable", json={
        "document_id": created["document_id"],
        "dataset_config": {"provider": "mock", "dataset_id": "mock-dataset"},
    }, headers=HEADERS)
    assert disable.status_code == 200
    assert disable.json()["disabled"] is True


def test_dataset_endpoints_require_internal_token_and_strict_input():
    config = {"provider": "mock", "dataset_id": "mock-dataset"}
    bodies = {
        "sync": sync_payload(),
        "status": {"batch": "mock-batch-1", "document_id": "mock-document-1", "dataset_config": config},
        "disable": {"document_id": "mock-document-1", "dataset_config": config},
    }
    for path, body in bodies.items():
        resp = client.post(f"/api/v1/knowledge/documents/{path}", json=body)
        assert resp.status_code == 401
        assert resp.json()["code"] == "UNAUTHORIZED"

    extra = {**sync_payload(), "tenant_id": 2, "dataset_id": "untrusted"}
    resp = client.post("/api/v1/knowledge/documents/sync", json=extra, headers=HEADERS)
    assert resp.status_code == 422
    assert resp.json()["code"] == "VALIDATION_FAILED"

    for text in (" ", "知" * 1_048_577):
        body = {**sync_payload(), "text": text}
        resp = client.post("/api/v1/knowledge/documents/sync", json=body, headers=HEADERS)
        assert resp.status_code == 422


def test_dataset_disabled_provider_does_not_fall_back_to_mock():
    body = sync_payload()
    body["dataset_config"] = {"provider": "disabled"}
    resp = client.post("/api/v1/knowledge/documents/sync", json=body, headers=HEADERS)

    assert resp.status_code == 503
    assert resp.json()["code"] == "AI_PROVIDER_DISABLED"
