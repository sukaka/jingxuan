import json

import httpx
import pytest

from app.config import settings
from app.schemas import (
    KnowledgeDatasetConfig,
    KnowledgeDocumentDisableRequest,
    KnowledgeDocumentMetadata,
    KnowledgeDocumentStatusRequest,
    KnowledgeDocumentSyncRequest,
)
from app.services.knowledge import AiKnowledgeError
from app.services.knowledge_dataset import DifyDatasetAdapter, MockDatasetAdapter, get_dataset_adapter


def dataset_config(provider="dify") -> KnowledgeDatasetConfig:
    return KnowledgeDatasetConfig.model_validate({
        "provider": provider,
        "base_url": "https://dify.example.test/v1/",
        "api_key": "dataset-synthetic-key",
        "dataset_id": "dataset-1",
        "timeout_seconds": 9.5,
    })


def sync_request(**overrides) -> KnowledgeDocumentSyncRequest:
    data = {
        "job_id": "job-7",
        "name": "服务流程-v2.md",
        "text": "# 服务流程\n仅用于合成知识资料。",
        "metadata": {
            "tenant_id": "1",
            "material_id": "10",
            "revision_id": "20",
            "revision_no": 2,
            "category_code": "SERVICE_GUIDE",
            "institution_id": None,
            "project_id": "30",
            "visibility": "STAFF_INTERNAL",
            "content_hash": "a" * 64,
        },
        "dataset_config": dataset_config().model_dump(),
    }
    data.update(overrides)
    return KnowledgeDocumentSyncRequest.model_validate(data)


def status_request(**overrides) -> KnowledgeDocumentStatusRequest:
    data = {"batch": "batch-1", "document_id": "document-1", "dataset_config": dataset_config().model_dump()}
    data.update(overrides)
    return KnowledgeDocumentStatusRequest.model_validate(data)


def enable_real(monkeypatch):
    monkeypatch.setattr(settings, "feature_real_dify", True)


def test_dify_create_document_sends_only_required_public_fields(monkeypatch):
    enable_real(monkeypatch)

    def handler(req: httpx.Request) -> httpx.Response:
        assert req.method == "POST"
        assert str(req.url) == "https://dify.example.test/v1/datasets/dataset-1/document/create-by-text"
        assert req.headers["Authorization"] == "Bearer dataset-synthetic-key"
        assert req.extensions["timeout"] == {"connect": 9.5, "read": 9.5, "write": 9.5, "pool": 9.5}
        body = json.loads(req.content)
        assert body == {
            "name": "服务流程-v2.md",
            "text": "# 服务流程\n仅用于合成知识资料。",
            "indexing_technique": "high_quality",
            "process_rule": {"mode": "automatic"},
        }
        serialized = json.dumps(body, ensure_ascii=False)
        assert "tenant_id" not in serialized
        assert "dataset-synthetic-key" not in serialized
        return httpx.Response(200, json={
            "document": {"id": "document-1", "indexing_status": "indexing"},
            "batch": "batch-1",
        })

    result = DifyDatasetAdapter(dataset_config(), transport=httpx.MockTransport(handler)).sync(sync_request())

    assert result.provider == "dify"
    assert result.document_id == "document-1"
    assert result.batch == "batch-1"
    assert result.indexing_status == "INDEXING"


def test_dify_update_document_uses_existing_id(monkeypatch):
    enable_real(monkeypatch)

    def handler(req: httpx.Request) -> httpx.Response:
        assert str(req.url) == "https://dify.example.test/v1/datasets/dataset-1/documents/document-9/update-by-text"
        body = json.loads(req.content)
        assert body["text"].startswith("# 服务流程")
        assert "indexing_technique" not in body
        return httpx.Response(200, json={"document": {"id": "document-9", "indexing_status": "indexing"}, "batch": "batch-9"})

    result = DifyDatasetAdapter(dataset_config(), transport=httpx.MockTransport(handler)).sync(
        sync_request(document_id="document-9")
    )
    assert result.document_id == "document-9"
    assert result.batch == "batch-9"


def test_dify_indexing_status_requires_matching_document_and_maps_terminal_states(monkeypatch):
    enable_real(monkeypatch)

    def handler(req: httpx.Request) -> httpx.Response:
        assert req.method == "GET"
        assert str(req.url) == "https://dify.example.test/v1/datasets/dataset-1/documents/batch-1/indexing-status"
        return httpx.Response(200, json={"data": [{"id": "document-1", "indexing_status": "completed", "error": None}]})

    result = DifyDatasetAdapter(dataset_config(), transport=httpx.MockTransport(handler)).status(status_request())
    assert result.indexing_status == "AVAILABLE"
    assert result.error_masked is None

    failed_transport = httpx.MockTransport(lambda _: httpx.Response(200, json={
        "data": [{"id": "document-1", "indexing_status": "error", "error": "upstream sensitive details"}]
    }))
    failed = DifyDatasetAdapter(dataset_config(), transport=failed_transport).status(status_request())
    assert failed.indexing_status == "FAILED"
    assert failed.error_masked == "Dify 文档索引失败"
    assert "upstream" not in failed.error_masked


def test_dify_status_rejects_mismatched_document(monkeypatch):
    enable_real(monkeypatch)
    transport = httpx.MockTransport(lambda _: httpx.Response(200, json={
        "data": [{"id": "another-document", "indexing_status": "completed"}]
    }))
    with pytest.raises(AiKnowledgeError) as exc_info:
        DifyDatasetAdapter(dataset_config(), transport=transport).status(status_request())
    assert exc_info.value.code == "AI_OUTPUT_INVALID"


def test_dify_disable_document_uses_status_not_delete(monkeypatch):
    enable_real(monkeypatch)

    def handler(req: httpx.Request) -> httpx.Response:
        assert req.method == "PATCH"
        assert str(req.url) == "https://dify.example.test/v1/datasets/dataset-1/documents/document-1/status/disable"
        return httpx.Response(200, json={"result": "success"})

    request = KnowledgeDocumentDisableRequest(document_id="document-1", dataset_config=dataset_config())
    result = DifyDatasetAdapter(dataset_config(), transport=httpx.MockTransport(handler)).disable(request)
    assert result.disabled is True


def test_real_dataset_switch_blocks_before_http(monkeypatch):
    monkeypatch.setattr(settings, "feature_real_dify", False)
    called = False

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200, json={})

    adapter = DifyDatasetAdapter(dataset_config(), transport=httpx.MockTransport(handler))
    with pytest.raises(AiKnowledgeError) as exc_info:
        adapter.sync(sync_request())
    assert exc_info.value.code == "AI_PROVIDER_DISABLED"
    assert called is False


def test_dataset_failures_are_masked_and_malformed_output_rejected(monkeypatch):
    enable_real(monkeypatch)
    unauthorized = DifyDatasetAdapter(dataset_config(), transport=httpx.MockTransport(
        lambda _: httpx.Response(401, json={"error": "leaked dataset-synthetic-key"})
    ))
    with pytest.raises(AiKnowledgeError) as exc_info:
        unauthorized.sync(sync_request())
    assert exc_info.value.code == "AI_PROVIDER_FAILED"
    assert "dataset-synthetic-key" not in str(exc_info.value)

    malformed = DifyDatasetAdapter(dataset_config(), transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json={"document": {"id": "missing-batch"}})
    ))
    with pytest.raises(AiKnowledgeError) as exc_info:
        malformed.sync(sync_request())
    assert exc_info.value.code == "AI_OUTPUT_INVALID"


def test_mock_dataset_sync_status_and_disable_are_deterministic():
    adapter = MockDatasetAdapter()
    created = adapter.sync(sync_request())
    assert created.provider == "mock"
    assert created.document_id == "mock-document-job-7"
    assert created.batch == "mock-batch-job-7"
    assert created.indexing_status == "AVAILABLE"

    updated = adapter.sync(sync_request(document_id="existing-document"))
    assert updated.document_id == "existing-document"

    status = adapter.status(status_request(batch=created.batch, document_id=created.document_id,
                                           dataset_config=dataset_config("mock")))
    assert status.indexing_status == "AVAILABLE"
    disabled = adapter.disable(KnowledgeDocumentDisableRequest(
        document_id=created.document_id, dataset_config=dataset_config("mock")
    ))
    assert disabled.disabled is True


def test_dataset_factory_rejects_disabled_and_incomplete_real_config(monkeypatch):
    with pytest.raises(AiKnowledgeError) as exc_info:
        get_dataset_adapter(dataset_config("disabled"))
    assert exc_info.value.code == "AI_PROVIDER_DISABLED"

    enable_real(monkeypatch)
    incomplete = dataset_config().model_dump()
    incomplete["dataset_id"] = ""
    with pytest.raises(AiKnowledgeError) as exc_info:
        get_dataset_adapter(KnowledgeDatasetConfig.model_validate(incomplete))
    assert exc_info.value.code == "AI_PROVIDER_NOT_CONFIGURED"
