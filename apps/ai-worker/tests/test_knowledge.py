import json

import httpx
import pytest

from app.config import settings
from app.schemas import KnowledgeChatRequest
from app.services.knowledge import (
    AiKnowledgeError,
    DifyKnowledgeAdapter,
    MockKnowledgeAdapter,
    get_knowledge_adapter,
    validate_dify_base_url,
)


def request(provider: str = "dify", **overrides) -> KnowledgeChatRequest:
    data = {
        "question": "请说明标准服务流程",
        "end_user_id": "tenant-1-user-9",
        "provider_config": {
            "provider": provider,
            "base_url": "https://dify.example.test/v1/",
            "api_key": "dify-synthetic-key",
            "timeout_seconds": 12.5,
        },
    }
    data.update(overrides)
    return KnowledgeChatRequest.model_validate(data)


def enable_real(monkeypatch):
    monkeypatch.setattr(settings, "feature_real_dify", True)


def test_mock_adapter_is_explicit_and_does_not_need_real_dify():
    result = MockKnowledgeAdapter().chat(request("mock"))

    assert result.provider == "mock"
    assert "模拟" in result.answer
    assert result.citations == []


def test_dify_adapter_sends_blocking_chat_and_normalizes_citations(monkeypatch):
    enable_real(monkeypatch)

    def handler(req: httpx.Request) -> httpx.Response:
        assert str(req.url) == "https://dify.example.test/v1/chat-messages"
        assert req.headers["Authorization"] == "Bearer dify-synthetic-key"
        assert req.extensions["timeout"] == {"connect": 12.5, "read": 12.5, "write": 12.5, "pool": 12.5}
        body = json.loads(req.content)
        assert body == {
            "inputs": {},
            "query": "请说明标准服务流程",
            "response_mode": "blocking",
            "conversation_id": "conversation-7",
            "user": "tenant-1-user-9",
        }
        assert "dify-synthetic-key" not in json.dumps(body)
        return httpx.Response(200, json={
            "event": "message",
            "message_id": "message-1",
            "conversation_id": "conversation-7",
            "answer": "仅依据资料回答",
            "metadata": {
                "retriever_resources": [{
                    "document_id": "document-1",
                    "document_name": "服务流程",
                    "segment_id": "segment-1",
                    "content": "资料片段",
                    "score": 0.91,
                }],
                "usage": {"total_tokens": 20},
            },
        })

    adapter = DifyKnowledgeAdapter(
        "https://dify.example.test/v1/", "dify-synthetic-key", 12.5,
        transport=httpx.MockTransport(handler),
    )
    result = adapter.chat(request(conversation_id="conversation-7"))

    assert result.provider == "dify"
    assert result.answer == "仅依据资料回答"
    assert result.message_id == "message-1"
    assert result.conversation_id == "conversation-7"
    assert result.citations[0].document_id == "document-1"
    assert result.citations[0].content == "资料片段"
    assert result.model_dump().get("metadata") is None


def test_real_dify_switch_blocks_before_any_http_request(monkeypatch):
    monkeypatch.setattr(settings, "feature_real_dify", False)
    called = False

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200)

    adapter = DifyKnowledgeAdapter(
        "https://dify.example.test/v1", "dify-synthetic-key",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(AiKnowledgeError) as exc_info:
        adapter.chat(request())

    assert exc_info.value.code == "AI_PROVIDER_DISABLED"
    assert called is False


def test_dify_adapter_masks_upstream_failure(monkeypatch):
    enable_real(monkeypatch)
    transport = httpx.MockTransport(lambda _: httpx.Response(401, json={"error": "leaked dify-synthetic-key"}))
    adapter = DifyKnowledgeAdapter("https://dify.example.test/v1", "dify-synthetic-key", transport=transport)

    with pytest.raises(AiKnowledgeError) as exc_info:
        adapter.chat(request())

    assert exc_info.value.code == "AI_PROVIDER_FAILED"
    assert "dify-synthetic-key" not in str(exc_info.value)
    assert "leaked" not in str(exc_info.value)


@pytest.mark.parametrize("response", [
    httpx.Response(200, content="not-json"),
    httpx.Response(200, json={"answer": "缺少会话和消息标识"}),
    httpx.Response(200, json={"message_id": "m", "conversation_id": "c", "answer": "", "metadata": {}}),
])
def test_dify_adapter_rejects_invalid_output(monkeypatch, response):
    enable_real(monkeypatch)
    adapter = DifyKnowledgeAdapter(
        "https://dify.example.test/v1", "dify-synthetic-key",
        transport=httpx.MockTransport(lambda _: response),
    )
    with pytest.raises(AiKnowledgeError) as exc_info:
        adapter.chat(request())
    assert exc_info.value.code == "AI_OUTPUT_INVALID"


def test_dify_adapter_does_not_follow_redirects(monkeypatch):
    enable_real(monkeypatch)
    adapter = DifyKnowledgeAdapter(
        "https://dify.example.test/v1", "dify-synthetic-key",
        transport=httpx.MockTransport(lambda _: httpx.Response(302, headers={"Location": "http://127.0.0.1/admin"})),
    )
    with pytest.raises(AiKnowledgeError) as exc_info:
        adapter.chat(request())
    assert exc_info.value.code == "AI_PROVIDER_FAILED"


def test_http_url_requires_explicit_internal_opt_in(monkeypatch):
    monkeypatch.setattr(settings, "ai_dify_allow_http", False)
    with pytest.raises(AiKnowledgeError, match="HTTPS"):
        validate_dify_base_url("http://127.0.0.1:8080/v1")

    monkeypatch.setattr(settings, "ai_dify_allow_http", True)
    assert validate_dify_base_url("http://127.0.0.1:8080/v1/") == "http://127.0.0.1:8080/v1"
    with pytest.raises(AiKnowledgeError, match="内网"):
        validate_dify_base_url("http://8.8.8.8/v1")


@pytest.mark.parametrize("url", [
    "ftp://dify.example.test/v1",
    "https://user:secret@dify.example.test/v1",
    "https://dify.example.test/v1?key=secret",
    "https://dify.example.test/v1#fragment",
])
def test_invalid_dify_urls_are_rejected(url):
    with pytest.raises(AiKnowledgeError):
        validate_dify_base_url(url)


def test_disabled_provider_and_missing_real_configuration_are_stable_errors(monkeypatch):
    with pytest.raises(AiKnowledgeError) as exc_info:
        get_knowledge_adapter(request("disabled"))
    assert exc_info.value.code == "AI_PROVIDER_DISABLED"

    enable_real(monkeypatch)
    data = request().model_dump(mode="json")
    data["provider_config"]["base_url"] = ""
    with pytest.raises(AiKnowledgeError) as exc_info:
        get_knowledge_adapter(KnowledgeChatRequest.model_validate(data))
    assert exc_info.value.code == "AI_PROVIDER_NOT_CONFIGURED"
