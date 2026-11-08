import json
from typing import Protocol
from urllib.parse import quote

import httpx
from pydantic import ValidationError

from app.config import settings
from app.schemas import (
    KnowledgeDatasetConfig,
    KnowledgeDocumentDisableRequest,
    KnowledgeDocumentDisableResult,
    KnowledgeDocumentStatusRequest,
    KnowledgeDocumentSyncRequest,
    KnowledgeDocumentSyncResult,
)
from app.services.knowledge import AiKnowledgeError, validate_dify_base_url


class DatasetAdapter(Protocol):
    def sync(self, request: KnowledgeDocumentSyncRequest) -> KnowledgeDocumentSyncResult: ...

    def status(self, request: KnowledgeDocumentStatusRequest) -> KnowledgeDocumentSyncResult: ...

    def disable(self, request: KnowledgeDocumentDisableRequest) -> KnowledgeDocumentDisableResult: ...


class MockDatasetAdapter:
    def sync(self, request: KnowledgeDocumentSyncRequest) -> KnowledgeDocumentSyncResult:
        return KnowledgeDocumentSyncResult(
            provider="mock",
            document_id=request.document_id or f"mock-document-{request.job_id}",
            batch=f"mock-batch-{request.job_id}",
            indexing_status="AVAILABLE",
        )

    def status(self, request: KnowledgeDocumentStatusRequest) -> KnowledgeDocumentSyncResult:
        return KnowledgeDocumentSyncResult(
            provider="mock",
            document_id=request.document_id,
            batch=request.batch,
            indexing_status="AVAILABLE",
        )

    def disable(self, request: KnowledgeDocumentDisableRequest) -> KnowledgeDocumentDisableResult:
        return KnowledgeDocumentDisableResult(provider="mock", document_id=request.document_id, disabled=True)


class DifyDatasetAdapter:
    def __init__(self, config: KnowledgeDatasetConfig, transport: httpx.BaseTransport | None = None):
        if not config.base_url.strip() or not config.api_key.strip() or not config.dataset_id.strip():
            raise AiKnowledgeError("AI_PROVIDER_NOT_CONFIGURED", "Dify Dataset 配置不完整", 503)
        self.config = config
        self.transport = transport

    def sync(self, request: KnowledgeDocumentSyncRequest) -> KnowledgeDocumentSyncResult:
        if request.document_id:
            path = f"/documents/{quote(request.document_id, safe='')}/update-by-text"
            payload = {
                "name": request.name,
                "text": request.text,
                "process_rule": {"mode": "automatic"},
            }
        else:
            path = "/document/create-by-text"
            payload = {
                "name": request.name,
                "text": request.text,
                "indexing_technique": "high_quality",
                "process_rule": {"mode": "automatic"},
            }
        try:
            body = self._request("POST", path, payload).json()
            if not isinstance(body, dict) or not isinstance(body.get("document"), dict):
                raise ValueError("Dify 文档响应缺少 document")
            document = body["document"]
            return KnowledgeDocumentSyncResult(
                provider="dify",
                document_id=document.get("id"),
                batch=body.get("batch"),
                indexing_status=self._map_status(document.get("indexing_status")),
            )
        except (json.JSONDecodeError, ValidationError, TypeError, ValueError) as exc:
            raise AiKnowledgeError("AI_OUTPUT_INVALID", "Dify 文档响应未通过结构校验") from exc

    def status(self, request: KnowledgeDocumentStatusRequest) -> KnowledgeDocumentSyncResult:
        try:
            body = self._request("GET", f"/documents/{quote(request.batch, safe='')}/indexing-status").json()
            data = body.get("data") if isinstance(body, dict) else None
            if not isinstance(data, list):
                raise ValueError("Dify 索引响应缺少 data")
            matches = [item for item in data if isinstance(item, dict) and item.get("id") == request.document_id]
            if len(matches) != 1:
                raise ValueError("Dify 索引响应与 document_id 不匹配")
            item = matches[0]
            status = self._map_status(item.get("indexing_status"))
            return KnowledgeDocumentSyncResult(
                provider="dify",
                document_id=request.document_id,
                batch=request.batch,
                indexing_status=status,
                error_masked="Dify 文档索引失败" if status == "FAILED" else None,
            )
        except (json.JSONDecodeError, ValidationError, TypeError, ValueError) as exc:
            raise AiKnowledgeError("AI_OUTPUT_INVALID", "Dify 索引响应未通过结构校验") from exc

    def disable(self, request: KnowledgeDocumentDisableRequest) -> KnowledgeDocumentDisableResult:
        self._request("PATCH", f"/documents/{quote(request.document_id, safe='')}/status/disable")
        return KnowledgeDocumentDisableResult(provider="dify", document_id=request.document_id, disabled=True)

    def _request(self, method: str, path: str, payload: dict | None = None) -> httpx.Response:
        if not settings.feature_real_dify:
            raise AiKnowledgeError("AI_PROVIDER_DISABLED", "真实 Dify 接入未启用", 503)
        base_url = validate_dify_base_url(self.config.base_url)
        dataset_id = quote(self.config.dataset_id, safe="")
        try:
            with httpx.Client(
                timeout=self.config.timeout_seconds,
                transport=self.transport,
                follow_redirects=False,
            ) as client:
                response = client.request(
                    method,
                    f"{base_url}/datasets/{dataset_id}{path}",
                    headers={"Authorization": f"Bearer {self.config.api_key}"},
                    json=payload,
                )
            response.raise_for_status()
            return response
        except (httpx.HTTPError, OSError) as exc:
            raise AiKnowledgeError("AI_PROVIDER_FAILED", "Dify Dataset 服务暂时不可用") from exc

    @staticmethod
    def _map_status(value: str) -> str:
        if value in ("waiting", "indexing"):
            return "INDEXING"
        if value in ("completed", "success"):
            return "AVAILABLE"
        if value in ("error", "paused"):
            return "FAILED"
        raise ValueError("未知 Dify 索引状态")


def get_dataset_adapter(config: KnowledgeDatasetConfig) -> DatasetAdapter:
    if config.provider == "mock":
        return MockDatasetAdapter()
    if config.provider == "dify":
        if not settings.feature_real_dify:
            raise AiKnowledgeError("AI_PROVIDER_DISABLED", "真实 Dify 接入未启用", 503)
        return DifyDatasetAdapter(config)
    raise AiKnowledgeError("AI_PROVIDER_DISABLED", "Dify Dataset 服务未启用", 503)
