import ipaddress
import json
import socket
from typing import Protocol
from urllib.parse import urlsplit

import httpx
from pydantic import ValidationError

from app.config import settings
from app.schemas import KnowledgeChatRequest, KnowledgeCitation, KnowledgeResult


class AiKnowledgeError(Exception):
    def __init__(self, code: str, message: str, status_code: int = 502):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


def _configuration_error(message: str) -> AiKnowledgeError:
    return AiKnowledgeError("AI_PROVIDER_NOT_CONFIGURED", message, 503)


def _is_internal(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    return address.is_loopback or address.is_link_local or address.is_private


def validate_dify_base_url(value: str) -> str:
    normalized = value.strip().rstrip("/")
    try:
        parsed = urlsplit(normalized)
        if parsed.hostname is None or parsed.username is not None or parsed.password is not None:
            raise ValueError
        if parsed.query or parsed.fragment:
            raise ValueError
        scheme = parsed.scheme.lower()
        if scheme == "https":
            return normalized
        if scheme != "http":
            raise ValueError
        if not settings.ai_dify_allow_http:
            raise _configuration_error("Dify baseUrl 默认必须使用 HTTPS")
        addresses = {
            item[4][0].split("%")[0]
            for item in socket.getaddrinfo(parsed.hostname, parsed.port or 80, type=socket.SOCK_STREAM)
        }
        if not addresses or any(not _is_internal(ipaddress.ip_address(address)) for address in addresses):
            raise _configuration_error("仅允许明确配置的内网 HTTP 地址")
        return normalized
    except AiKnowledgeError:
        raise
    except (ValueError, OSError) as exc:
        raise _configuration_error("Dify baseUrl 格式无效或地址不可解析") from exc


class KnowledgeAdapter(Protocol):
    def chat(self, request: KnowledgeChatRequest) -> KnowledgeResult: ...


class MockKnowledgeAdapter:
    def chat(self, request: KnowledgeChatRequest) -> KnowledgeResult:
        return KnowledgeResult(
            provider="mock",
            answer="模拟知识问答连接成功；此结果不代表真实 Dify、知识检索或模型效果已验证。",
            message_id="mock-knowledge-message",
            conversation_id="mock-knowledge-conversation",
            citations=[],
        )


class DifyKnowledgeAdapter:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        timeout_seconds: float = 30,
        transport: httpx.BaseTransport | None = None,
    ):
        if not base_url.strip() or not api_key.strip():
            raise _configuration_error("Dify 必须完整配置 baseUrl 和 API Key")
        self.base_url = base_url.strip().rstrip("/")
        self.api_key = api_key.strip()
        self.timeout_seconds = timeout_seconds
        self.transport = transport

    def chat(self, request: KnowledgeChatRequest) -> KnowledgeResult:
        if not settings.feature_real_dify:
            raise AiKnowledgeError("AI_PROVIDER_DISABLED", "真实 Dify 接入未启用", 503)
        base_url = validate_dify_base_url(self.base_url)
        payload = {
            "inputs": {},
            "query": request.question,
            "response_mode": "blocking",
            "user": request.end_user_id,
        }
        if request.conversation_id is not None:
            payload["conversation_id"] = request.conversation_id
        try:
            with httpx.Client(
                timeout=self.timeout_seconds,
                transport=self.transport,
                follow_redirects=False,
            ) as client:
                response = client.post(
                    f"{base_url}/chat-messages",
                    headers={"Authorization": f"Bearer {self.api_key}"},
                    json=payload,
                )
            response.raise_for_status()
            body = response.json()
            return self._parse_result(body)
        except (httpx.HTTPError, OSError) as exc:
            raise AiKnowledgeError("AI_PROVIDER_FAILED", "Dify 知识服务暂时不可用") from exc
        except (json.JSONDecodeError, ValidationError, TypeError, ValueError) as exc:
            raise AiKnowledgeError("AI_OUTPUT_INVALID", "Dify 输出未通过结构校验") from exc

    @staticmethod
    def _parse_result(body: dict) -> KnowledgeResult:
        if not isinstance(body, dict):
            raise ValueError("Dify 响应不是对象")
        if body.get("event") not in (None, "message", "agent_message"):
            raise ValueError("Dify 响应事件不是完整消息")
        metadata = body.get("metadata") or {}
        if not isinstance(metadata, dict):
            raise ValueError("Dify metadata 无效")
        resources = metadata.get("retriever_resources") or []
        if not isinstance(resources, list):
            raise ValueError("Dify 引用不是列表")
        citations = []
        for resource in resources:
            if not isinstance(resource, dict):
                raise ValueError("Dify 引用项无效")
            citations.append(KnowledgeCitation(
                document_id=resource.get("document_id"),
                document_name=resource.get("document_name") or "",
                segment_id=resource.get("segment_id"),
                content=resource.get("content"),
                score=resource.get("score"),
            ))
        return KnowledgeResult(
            provider="dify",
            answer=body.get("answer"),
            message_id=body.get("message_id") or body.get("id"),
            conversation_id=body.get("conversation_id"),
            citations=citations,
        )


def get_knowledge_adapter(request: KnowledgeChatRequest) -> KnowledgeAdapter:
    config = request.provider_config
    if config.provider == "mock":
        return MockKnowledgeAdapter()
    if config.provider == "dify":
        if not settings.feature_real_dify:
            raise AiKnowledgeError("AI_PROVIDER_DISABLED", "真实 Dify 接入未启用", 503)
        return DifyKnowledgeAdapter(config.base_url, config.api_key, config.timeout_seconds)
    raise AiKnowledgeError("AI_PROVIDER_DISABLED", "Dify 知识服务未启用", 503)
