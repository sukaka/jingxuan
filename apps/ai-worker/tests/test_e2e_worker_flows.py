import json
import threading
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import create_app

INTERNAL_TOKEN = "e2e-internal-secret-token"
AUTH_HEADERS = {"X-AI-Internal-Token": INTERNAL_TOKEN}


@dataclass
class RecordedRequest:
    method: str
    path: str
    headers: dict[str, str]
    body: dict[str, Any] | None


@dataclass
class StubRouteResponse:
    status_code: int = 200
    body: dict[str, Any] = field(default_factory=dict)
    headers: dict[str, str] = field(default_factory=dict)


class StubUpstreamServer:
    def __init__(self) -> None:
        self.requests: list[RecordedRequest] = []
        self.routes: dict[tuple[str, str], StubRouteResponse] = {}
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self, method: str) -> None:
                content_length = int(self.headers.get("Content-Length", "0") or "0")
                raw = self.rfile.read(content_length) if content_length > 0 else b""
                parsed_body = json.loads(raw.decode("utf-8")) if raw else None
                parent.requests.append(
                    RecordedRequest(
                        method=method,
                        path=self.path,
                        headers={k.lower(): v for k, v in self.headers.items()},
                        body=parsed_body,
                    )
                )
                route = parent.routes.get((method, self.path))
                if route is None:
                    self.send_response(404)
                    self.end_headers()
                    return
                payload = json.dumps(route.body).encode("utf-8") if route.body else b""
                self.send_response(route.status_code)
                for key, value in route.headers.items():
                    self.send_header(key, value)
                if payload:
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                if payload:
                    self.wfile.write(payload)

            def do_GET(self) -> None:  # noqa: N802
                self._handle("GET")

            def do_POST(self) -> None:  # noqa: N802
                self._handle("POST")

            def do_PATCH(self) -> None:  # noqa: N802
                self._handle("PATCH")

            def log_message(self, format: str, *args: Any) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        self.base_url = f"http://127.0.0.1:{self.port}/v1"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def set_route(
        self,
        method: str,
        path: str,
        *,
        status_code: int = 200,
        body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        self.routes[(method, path)] = StubRouteResponse(
            status_code=status_code,
            body=body or {},
            headers=headers or {},
        )

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)


@pytest.fixture
def stub_server():
    server = StubUpstreamServer()
    try:
        yield server
    finally:
        server.close()


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(settings, "ai_internal_token", INTERNAL_TOKEN)
    return TestClient(create_app())


def _extract_payload(provider_config: dict[str, Any] | None = None) -> dict[str, Any]:
    body: dict[str, Any] = {
        "job_id": "e2e-job-101",
        "customer_id": "CUST-9001",
        "messages": [
            {
                "id": "msg-001",
                "sender_type": "CUSTOMER",
                "sent_at": "2026-10-26T09:00:00Z",
                "text": "我想下周三去朝阳医院做基础水光，方便安排吗？",
            }
        ],
        "projects": [{"code": "BASIC_HYDRATION", "name": "基础水光"}],
        "institutions": [{"id": "INST-1", "name": "朝阳医院"}],
    }
    if provider_config is not None:
        body["provider_config"] = provider_config
    return body


def _reply_payload(provider_config: dict[str, Any] | None = None) -> dict[str, Any]:
    body: dict[str, Any] = {
        "suggestion_id": "sug-e2e-1",
        "customer_id": "CUST-9001",
        "scene": "PACKAGE_REMAINING",
        "facts": {
            "customer_name": "林女士",
            "project_name": "基础水光",
            "total_count": 3,
            "used_count": 1,
            "remaining_count": 2,
        },
        "instruction": "语气专业温和，仅引用已核实事实",
    }
    if provider_config is not None:
        body["provider_config"] = provider_config
    return body


def _knowledge_chat_payload(provider_config: dict[str, Any]) -> dict[str, Any]:
    return {
        "question": "基础水光术后24小时需要注意什么？",
        "end_user_id": "staff-1001",
        "conversation_id": "conv-existing-1",
        "provider_config": provider_config,
    }


def _knowledge_sync_payload(
    dataset_config: dict[str, Any],
    *,
    document_id: str | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "job_id": "ksync-1",
        "name": "基础水光术后指引.md",
        "text": "# 术后指引\n24小时内避免沾水与高温环境。",
        "metadata": {
            "tenant_id": "1",
            "material_id": "55",
            "revision_id": "77",
            "revision_no": 3,
            "category_code": "AFTERCARE",
            "institution_id": "10",
            "project_id": "20",
            "visibility": "STAFF_INTERNAL",
            "content_hash": "a" * 64,
        },
        "dataset_config": dataset_config,
    }
    if document_id is not None:
        payload["document_id"] = document_id
    return payload


def test_e2e_health_and_request_id_handling(client):
    resp_custom = client.get("/health", headers={"X-Request-Id": "e2e-health-req-1"})
    assert resp_custom.status_code == 200
    assert resp_custom.json() == {"status": "ok", "service": "ai-worker", "version": "0.1.0"}
    assert resp_custom.headers["X-Request-Id"] == "e2e-health-req-1"

    resp_generated = client.get("/health")
    assert resp_generated.status_code == 200
    generated_id = resp_generated.headers["X-Request-Id"]
    assert str(uuid.UUID(generated_id)) == generated_id


def test_e2e_internal_token_auth_enforced_across_all_endpoints(client):
    dataset_cfg = {"provider": "mock", "dataset_id": "ds-mock"}
    endpoints = [
        ("/api/v1/insights/extract", _extract_payload({"provider": "mock"})),
        ("/api/v1/replies/generate", _reply_payload({"provider": "mock"})),
        ("/api/v1/knowledge/chat", _knowledge_chat_payload({"provider": "mock"})),
        ("/api/v1/knowledge/documents/sync", _knowledge_sync_payload(dataset_cfg)),
        (
            "/api/v1/knowledge/documents/status",
            {"batch": "b-1", "document_id": "doc-1", "dataset_config": dataset_cfg},
        ),
        (
            "/api/v1/knowledge/documents/disable",
            {"document_id": "doc-1", "dataset_config": dataset_cfg},
        ),
    ]
    bad_headers = [
        {},
        {"X-AI-Internal-Token": "wrong-token"},
        {"X-AI-Internal-Token": "e2e-internal-secret-tokex"},  # same length mismatch
    ]
    for path, body in endpoints:
        for idx, extra_headers in enumerate(bad_headers):
            req_id = f"auth-fail-{idx}-{path.rsplit('/', 1)[-1]}"
            resp = client.post(path, json=body, headers={**extra_headers, "X-Request-Id": req_id})
            assert resp.status_code == 401
            err = resp.json()
            assert err["code"] == "UNAUTHORIZED"
            assert err["requestId"] == req_id
            assert resp.headers["X-Request-Id"] == req_id


def test_e2e_empty_configured_internal_token_rejects_requests(client, monkeypatch):
    monkeypatch.setattr(settings, "ai_internal_token", "")
    resp = client.post(
        "/api/v1/insights/extract",
        json=_extract_payload({"provider": "mock"}),
        headers={"X-AI-Internal-Token": ""},
    )
    assert resp.status_code == 401
    assert resp.json()["code"] == "UNAUTHORIZED"


def test_e2e_mock_mode_workflows_across_all_ai_endpoints(client, monkeypatch):
    monkeypatch.setattr(settings, "ai_provider", "mock")
    dataset_cfg = {"provider": "mock", "dataset_id": "ds-mock"}

    ext_resp = client.post(
        "/api/v1/insights/extract",
        json=_extract_payload(),
        headers={**AUTH_HEADERS, "X-Request-Id": "mock-ext-1"},
    )
    assert ext_resp.status_code == 200
    ext_body = ext_resp.json()
    assert ext_body["requestId"] == "mock-ext-1"
    assert ext_resp.headers["X-Request-Id"] == "mock-ext-1"
    assert ext_body["provider"] == "mock"
    assert ext_body["insight"]["insight_type"] == "APPOINTMENT_INTENT"
    assert ext_body["insight"]["project_codes"] == ["BASIC_HYDRATION"]
    assert ext_body["insight"]["mentioned_institution"] == "朝阳医院"
    assert ext_body["insight"]["fact_status"] == "UNVERIFIED"
    assert ext_body["insight"]["allow_financial_write"] is False

    rep_resp = client.post(
        "/api/v1/replies/generate",
        json=_reply_payload(),
        headers={**AUTH_HEADERS, "X-Request-Id": "mock-rep-1"},
    )
    assert rep_resp.status_code == 200
    rep_body = rep_resp.json()
    assert rep_body["requestId"] == "mock-rep-1"
    assert rep_body["provider"] == "mock"
    assert rep_body["suggestion"]["fact_status"] == "UNVERIFIED"
    assert rep_body["suggestion"]["allow_financial_write"] is False
    assert rep_body["suggestion"]["requires_human_review"] is True
    assert "基础水光" in rep_body["suggestion"]["reply"]

    chat_resp = client.post(
        "/api/v1/knowledge/chat",
        json=_knowledge_chat_payload({"provider": "mock"}),
        headers={**AUTH_HEADERS, "X-Request-Id": "mock-chat-1"},
    )
    assert chat_resp.status_code == 200
    assert chat_resp.json()["provider"] == "mock"
    assert chat_resp.json()["requestId"] == "mock-chat-1"

    sync_resp = client.post(
        "/api/v1/knowledge/documents/sync",
        json=_knowledge_sync_payload(dataset_cfg),
        headers={**AUTH_HEADERS, "X-Request-Id": "mock-sync-1"},
    )
    assert sync_resp.status_code == 200
    assert sync_resp.json()["provider"] == "mock"
    assert sync_resp.json()["indexing_status"] == "AVAILABLE"

    status_resp = client.post(
        "/api/v1/knowledge/documents/status",
        json={"batch": "mock-batch-ksync-1", "document_id": "mock-document-ksync-1", "dataset_config": dataset_cfg},
        headers=AUTH_HEADERS,
    )
    assert status_resp.status_code == 200
    assert status_resp.json()["indexing_status"] == "AVAILABLE"

    disable_resp = client.post(
        "/api/v1/knowledge/documents/disable",
        json={"document_id": "mock-document-ksync-1", "dataset_config": dataset_cfg},
        headers=AUTH_HEADERS,
    )
    assert disable_resp.status_code == 200
    assert disable_resp.json()["disabled"] is True


def test_e2e_openai_compatible_extract_and_reply_via_pinned_transport(client, stub_server, monkeypatch):
    monkeypatch.setattr(settings, "ai_allow_http", True)
    monkeypatch.setattr(settings, "ai_allowed_hosts", "127.0.0.1")

    stub_server.set_route(
        "POST",
        "/v1/chat/completions",
        body={
            "choices": [
                {
                    "message": {
                        "content": json.dumps(
                            {
                                "insight_type": "APPOINTMENT_INTENT",
                                "customer_id": "CUST-9001",
                                "project_codes": ["BASIC_HYDRATION"],
                                "mentioned_institution": "朝阳医院",
                                "mentioned_time": "下周三",
                                "absolute_time": None,
                                "evidence_message_ids": ["msg-001"],
                                "confidence": 0.91,
                                "proposed_actions": ["VERIFY_BALANCE", "CREATE_FOLLOWUP_DRAFT"],
                                "fact_status": "UNVERIFIED",
                                "allow_financial_write": False,
                                "reasoning_summary": "客户咨询下周三前往朝阳医院预约基础水光",
                                "reply_draft": "为您记录下周三基础水光预约意向，专属管家将核实后联系您。",
                                "followup_draft": {
                                    "title": "核实下周三基础水光预约",
                                    "description": "核实余次及朝阳医院排期后回访",
                                    "due_at": None,
                                },
                                "risk_flags": [],
                            }
                        )
                    }
                }
            ]
        },
    )

    provider_cfg = {
        "provider": "openai_compatible",
        "base_url": stub_server.base_url,
        "api_key": "sk-e2e-test-key",
        "model": "qwen-plus-e2e",
        "timeout_seconds": 15,
    }
    ext_resp = client.post(
        "/api/v1/insights/extract",
        json=_extract_payload(provider_cfg),
        headers={**AUTH_HEADERS, "X-Request-Id": "e2e-openai-ext-1"},
    )
    assert ext_resp.status_code == 200
    ext_data = ext_resp.json()
    assert ext_data["requestId"] == "e2e-openai-ext-1"
    assert ext_resp.headers["X-Request-Id"] == "e2e-openai-ext-1"
    assert ext_data["provider"] == "openai_compatible"
    assert ext_data["model"] == "qwen-plus-e2e"
    assert ext_data["insight"]["insight_type"] == "APPOINTMENT_INTENT"
    assert ext_data["insight"]["evidence_message_ids"] == ["msg-001"]

    # Switch stub response for reply generation
    stub_server.set_route(
        "POST",
        "/v1/chat/completions",
        body={
            "choices": [
                {
                    "message": {
                        "content": json.dumps(
                            {
                                "scene": "PACKAGE_REMAINING",
                                "reply": "林女士您好，您的基础水光套餐共3次，已使用1次，剩余2次。",
                                "reason": "严格引用CRM只读事实",
                                "confidence": 0.94,
                                "used_fact_codes": [
                                    "customer_name",
                                    "project_name",
                                    "total_count",
                                    "used_count",
                                    "remaining_count",
                                ],
                                "fact_status": "UNVERIFIED",
                                "allow_financial_write": False,
                                "requires_human_review": True,
                            }
                        )
                    }
                }
            ]
        },
    )

    rep_resp = client.post(
        "/api/v1/replies/generate",
        json=_reply_payload(provider_cfg),
        headers={**AUTH_HEADERS, "X-Request-Id": "e2e-openai-rep-1"},
    )
    assert rep_resp.status_code == 200
    rep_data = rep_resp.json()
    assert rep_data["requestId"] == "e2e-openai-rep-1"
    assert rep_resp.headers["X-Request-Id"] == "e2e-openai-rep-1"
    assert rep_data["provider"] == "openai_compatible"
    assert rep_data["model"] == "qwen-plus-e2e"
    assert rep_data["prompt_version"] == "reply-v1"
    assert rep_data["suggestion"]["requires_human_review"] is True

    assert len(stub_server.requests) == 2
    for outbound in stub_server.requests:
        assert outbound.method == "POST"
        assert outbound.path == "/v1/chat/completions"
        assert outbound.headers.get("authorization") == "Bearer sk-e2e-test-key"
        assert "x-ai-internal-token" not in outbound.headers
        assert outbound.body is not None
        assert outbound.body["model"] == "qwen-plus-e2e"


def test_e2e_dify_knowledge_chat_and_document_lifecycle_via_pinned_transport(client, stub_server, monkeypatch):
    monkeypatch.setattr(settings, "feature_real_dify", True)
    monkeypatch.setattr(settings, "ai_dify_allow_http", True)
    monkeypatch.setattr(settings, "ai_dify_allowed_hosts", "127.0.0.1")

    stub_server.set_route(
        "POST",
        "/v1/chat-messages",
        body={
            "event": "message",
            "answer": "基础水光术后24小时内请避免沾水并做好温和补水防晒。",
            "message_id": "dify-msg-001",
            "conversation_id": "conv-existing-1",
            "metadata": {
                "retriever_resources": [
                    {
                        "document_id": "dify-doc-88",
                        "document_name": "基础水光术后指引.md",
                        "segment_id": "seg-1",
                        "content": "24小时内避免沾水与高温环境。",
                        "score": 0.96,
                    }
                ]
            },
        },
    )
    stub_server.set_route(
        "POST",
        "/v1/datasets/ds-prod-1/document/create-by-text",
        body={"document": {"id": "dify-doc-88", "indexing_status": "waiting"}, "batch": "batch-88"},
    )
    stub_server.set_route(
        "POST",
        "/v1/datasets/ds-prod-1/documents/dify-doc-88/update-by-text",
        body={"document": {"id": "dify-doc-88", "indexing_status": "indexing"}, "batch": "batch-89"},
    )
    stub_server.set_route(
        "GET",
        "/v1/datasets/ds-prod-1/documents/batch-89/indexing-status",
        body={"data": [{"id": "dify-doc-88", "indexing_status": "completed"}]},
    )
    stub_server.set_route(
        "PATCH",
        "/v1/datasets/ds-prod-1/documents/dify-doc-88/status/disable",
        body={"result": "success"},
    )

    chat_resp = client.post(
        "/api/v1/knowledge/chat",
        json=_knowledge_chat_payload(
            {
                "provider": "dify",
                "base_url": stub_server.base_url,
                "api_key": "dify-app-secret",
                "timeout_seconds": 20,
            }
        ),
        headers={**AUTH_HEADERS, "X-Request-Id": "e2e-dify-chat-1"},
    )
    assert chat_resp.status_code == 200
    chat_body = chat_resp.json()
    assert chat_body["requestId"] == "e2e-dify-chat-1"
    assert chat_resp.headers["X-Request-Id"] == "e2e-dify-chat-1"
    assert chat_body["provider"] == "dify"
    assert chat_body["message_id"] == "dify-msg-001"
    assert len(chat_body["citations"]) == 1
    assert chat_body["citations"][0]["document_id"] == "dify-doc-88"

    dataset_cfg = {
        "provider": "dify",
        "base_url": stub_server.base_url,
        "api_key": "dify-dataset-secret",
        "dataset_id": "ds-prod-1",
        "timeout_seconds": 20,
    }

    create_resp = client.post(
        "/api/v1/knowledge/documents/sync",
        json=_knowledge_sync_payload(dataset_cfg),
        headers={**AUTH_HEADERS, "X-Request-Id": "e2e-dify-create-1"},
    )
    assert create_resp.status_code == 200
    assert create_resp.json()["requestId"] == "e2e-dify-create-1"
    assert create_resp.json()["document_id"] == "dify-doc-88"
    assert create_resp.json()["batch"] == "batch-88"
    assert create_resp.json()["indexing_status"] == "INDEXING"

    update_resp = client.post(
        "/api/v1/knowledge/documents/sync",
        json=_knowledge_sync_payload(dataset_cfg, document_id="dify-doc-88"),
        headers={**AUTH_HEADERS, "X-Request-Id": "e2e-dify-update-1"},
    )
    assert update_resp.status_code == 200
    assert update_resp.json()["batch"] == "batch-89"
    assert update_resp.json()["indexing_status"] == "INDEXING"

    status_resp = client.post(
        "/api/v1/knowledge/documents/status",
        json={"batch": "batch-89", "document_id": "dify-doc-88", "dataset_config": dataset_cfg},
        headers={**AUTH_HEADERS, "X-Request-Id": "e2e-dify-status-1"},
    )
    assert status_resp.status_code == 200
    assert status_resp.json()["requestId"] == "e2e-dify-status-1"
    assert status_resp.json()["indexing_status"] == "AVAILABLE"

    disable_resp = client.post(
        "/api/v1/knowledge/documents/disable",
        json={"document_id": "dify-doc-88", "dataset_config": dataset_cfg},
        headers={**AUTH_HEADERS, "X-Request-Id": "e2e-dify-disable-1"},
    )
    assert disable_resp.status_code == 200
    assert disable_resp.json()["requestId"] == "e2e-dify-disable-1"
    assert disable_resp.json()["disabled"] is True

    assert [(r.method, r.path) for r in stub_server.requests] == [
        ("POST", "/v1/chat-messages"),
        ("POST", "/v1/datasets/ds-prod-1/document/create-by-text"),
        ("POST", "/v1/datasets/ds-prod-1/documents/dify-doc-88/update-by-text"),
        ("GET", "/v1/datasets/ds-prod-1/documents/batch-89/indexing-status"),
        ("PATCH", "/v1/datasets/ds-prod-1/documents/dify-doc-88/status/disable"),
    ]
    assert stub_server.requests[0].headers.get("authorization") == "Bearer dify-app-secret"
    for req in stub_server.requests[1:]:
        assert req.headers.get("authorization") == "Bearer dify-dataset-secret"
        assert "x-ai-internal-token" not in req.headers


def test_e2e_ssrf_blocks_metadata_and_unallowlisted_loopback_over_http(client, stub_server, monkeypatch):
    monkeypatch.setattr(settings, "feature_real_dify", True)
    # Even when allow_http=True and 169.254.169.254 is listed, metadata/link-local is ALWAYS blocked
    monkeypatch.setattr(settings, "ai_allow_http", True)
    monkeypatch.setattr(settings, "ai_allowed_hosts", "169.254.169.254")
    monkeypatch.setattr(settings, "ai_dify_allow_http", True)
    monkeypatch.setattr(settings, "ai_dify_allowed_hosts", "169.254.169.254")

    for target_url in (
        "http://169.254.169.254/latest/meta-data",
        "https://169.254.169.254/v1",
        f"https://127.0.0.1:{stub_server.port}/v1",
    ):
        ext_resp = client.post(
            "/api/v1/insights/extract",
            json=_extract_payload(
                {
                    "provider": "openai_compatible",
                    "base_url": target_url,
                    "api_key": "sk-test",
                    "model": "m-test",
                }
            ),
            headers={**AUTH_HEADERS, "X-Request-Id": "ssrf-ext-block"},
        )
        assert ext_resp.status_code == 503
        assert ext_resp.json()["code"] == "AI_PROVIDER_NOT_CONFIGURED"
        assert ext_resp.json()["requestId"] == "ssrf-ext-block"

        rep_resp = client.post(
            "/api/v1/replies/generate",
            json=_reply_payload(
                {
                    "provider": "openai_compatible",
                    "base_url": target_url,
                    "api_key": "sk-test",
                    "model": "m-test",
                }
            ),
            headers=AUTH_HEADERS,
        )
        assert rep_resp.status_code == 503
        assert rep_resp.json()["code"] == "AI_PROVIDER_NOT_CONFIGURED"

        chat_resp = client.post(
            "/api/v1/knowledge/chat",
            json=_knowledge_chat_payload(
                {
                    "provider": "dify",
                    "base_url": target_url,
                    "api_key": "dify-key",
                }
            ),
            headers=AUTH_HEADERS,
        )
        assert chat_resp.status_code == 503
        assert chat_resp.json()["code"] == "AI_PROVIDER_NOT_CONFIGURED"

        sync_resp = client.post(
            "/api/v1/knowledge/documents/sync",
            json=_knowledge_sync_payload(
                {
                    "provider": "dify",
                    "base_url": target_url,
                    "api_key": "dify-key",
                    "dataset_id": "ds-1",
                }
            ),
            headers=AUTH_HEADERS,
        )
        assert sync_resp.status_code == 503
        assert sync_resp.json()["code"] == "AI_PROVIDER_NOT_CONFIGURED"

    # Also verify http://127.0.0.1 is blocked when allow_http=False and allowed_hosts=""
    monkeypatch.setattr(settings, "ai_allow_http", False)
    monkeypatch.setattr(settings, "ai_allowed_hosts", "")
    ext_http_denied = client.post(
        "/api/v1/insights/extract",
        json=_extract_payload(
            {
                "provider": "openai_compatible",
                "base_url": stub_server.base_url,
                "api_key": "sk-test",
                "model": "m-test",
            }
        ),
        headers=AUTH_HEADERS,
    )
    assert ext_http_denied.status_code == 503
    assert ext_http_denied.json()["code"] == "AI_PROVIDER_NOT_CONFIGURED"
    assert stub_server.requests == []


def test_e2e_http_302_redirect_to_metadata_is_rejected_without_following(client, stub_server, monkeypatch):
    monkeypatch.setattr(settings, "ai_allow_http", True)
    monkeypatch.setattr(settings, "ai_allowed_hosts", "127.0.0.1")
    monkeypatch.setattr(settings, "feature_real_dify", True)
    monkeypatch.setattr(settings, "ai_dify_allow_http", True)
    monkeypatch.setattr(settings, "ai_dify_allowed_hosts", "127.0.0.1")

    redirect_headers = {"Location": "http://169.254.169.254/latest/meta-data/iam/security-credentials"}
    stub_server.set_route("POST", "/v1/chat/completions", status_code=302, headers=redirect_headers)
    stub_server.set_route("POST", "/v1/chat-messages", status_code=302, headers=redirect_headers)
    stub_server.set_route("POST", "/v1/datasets/ds-1/document/create-by-text", status_code=302, headers=redirect_headers)
    stub_server.set_route("GET", "/v1/datasets/ds-1/documents/batch-1/indexing-status", status_code=302, headers=redirect_headers)
    stub_server.set_route("PATCH", "/v1/datasets/ds-1/documents/doc-1/status/disable", status_code=302, headers=redirect_headers)

    openai_cfg = {
        "provider": "openai_compatible",
        "base_url": stub_server.base_url,
        "api_key": "sk-test",
        "model": "m-test",
    }
    dify_chat_cfg = {"provider": "dify", "base_url": stub_server.base_url, "api_key": "dify-k"}
    dify_ds_cfg = {"provider": "dify", "base_url": stub_server.base_url, "api_key": "dify-k", "dataset_id": "ds-1"}

    responses = [
        client.post("/api/v1/insights/extract", json=_extract_payload(openai_cfg), headers=AUTH_HEADERS),
        client.post("/api/v1/replies/generate", json=_reply_payload(openai_cfg), headers=AUTH_HEADERS),
        client.post("/api/v1/knowledge/chat", json=_knowledge_chat_payload(dify_chat_cfg), headers=AUTH_HEADERS),
        client.post("/api/v1/knowledge/documents/sync", json=_knowledge_sync_payload(dify_ds_cfg), headers=AUTH_HEADERS),
        client.post(
            "/api/v1/knowledge/documents/status",
            json={"batch": "batch-1", "document_id": "doc-1", "dataset_config": dify_ds_cfg},
            headers=AUTH_HEADERS,
        ),
        client.post(
            "/api/v1/knowledge/documents/disable",
            json={"document_id": "doc-1", "dataset_config": dify_ds_cfg},
            headers=AUTH_HEADERS,
        ),
    ]
    for resp in responses:
        assert resp.status_code == 502
        assert resp.json()["code"] == "AI_PROVIDER_FAILED"

    assert len(stub_server.requests) == 6


def test_e2e_pydantic_extra_forbid_and_timeout_bounds_return_422(client):
    dataset_cfg = {"provider": "mock", "dataset_id": "ds-mock", "timeout_seconds": 30}

    unknown_field_cases = [
        ("/api/v1/insights/extract", {**_extract_payload({"provider": "mock"}), "extra_field": "x"}),
        (
            "/api/v1/insights/extract",
            _extract_payload({"provider": "mock", "unexpected_nested": True}),
        ),
        ("/api/v1/replies/generate", {**_reply_payload({"provider": "mock"}), "extra_field": "x"}),
        ("/api/v1/knowledge/chat", {**_knowledge_chat_payload({"provider": "mock"}), "extra_field": 1}),
        ("/api/v1/knowledge/documents/sync", {**_knowledge_sync_payload(dataset_cfg), "extra_field": "x"}),
        (
            "/api/v1/knowledge/documents/status",
            {"batch": "b-1", "document_id": "d-1", "dataset_config": dataset_cfg, "extra_field": "x"},
        ),
        (
            "/api/v1/knowledge/documents/disable",
            {"document_id": "d-1", "dataset_config": dataset_cfg, "extra_field": "x"},
        ),
    ]
    for path, body in unknown_field_cases:
        resp = client.post(path, json=body, headers={**AUTH_HEADERS, "X-Request-Id": "e2e-422-extra"})
        assert resp.status_code == 422
        assert resp.json()["code"] == "VALIDATION_FAILED"
        assert resp.json()["requestId"] == "e2e-422-extra"
        assert resp.headers["X-Request-Id"] == "e2e-422-extra"

    timeout_cases = [
        ("/api/v1/insights/extract", _extract_payload({"provider": "mock", "timeout_seconds": 300.1})),
        ("/api/v1/insights/extract", _extract_payload({"provider": "mock", "timeout_seconds": 0})),
        ("/api/v1/replies/generate", _reply_payload({"provider": "mock", "timeout_seconds": 301})),
        ("/api/v1/knowledge/chat", _knowledge_chat_payload({"provider": "mock", "timeout_seconds": 301})),
        (
            "/api/v1/knowledge/documents/sync",
            _knowledge_sync_payload({"provider": "mock", "dataset_id": "ds-1", "timeout_seconds": 301}),
        ),
        (
            "/api/v1/knowledge/documents/status",
            {
                "batch": "b-1",
                "document_id": "d-1",
                "dataset_config": {"provider": "mock", "dataset_id": "ds-1", "timeout_seconds": 301},
            },
        ),
        (
            "/api/v1/knowledge/documents/disable",
            {
                "document_id": "d-1",
                "dataset_config": {"provider": "mock", "dataset_id": "ds-1", "timeout_seconds": 301},
            },
        ),
    ]
    for path, body in timeout_cases:
        resp = client.post(path, json=body, headers=AUTH_HEADERS)
        assert resp.status_code == 422
        assert resp.json()["code"] == "VALIDATION_FAILED"
