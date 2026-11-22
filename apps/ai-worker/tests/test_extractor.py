import json

import httpx
import pytest
from pydantic import ValidationError

from app.config import settings
from app.schemas import ExtractionRequest, Insight
from app.services.evidence import validate_evidence
from app.services.extractor import AiExtractionError, MockExtractor, OpenAICompatibleExtractor, get_extractor


def request() -> ExtractionRequest:
    return ExtractionRequest.model_validate({
        "job_id": "job-1",
        "customer_id": "C00128",
        "messages": [{"id": "msg_001", "sender_type": "CUSTOMER", "sent_at": "2026-10-26T08:00:00Z", "text": "想预约基础水光"}],
        "projects": [{"code": "BASIC_HYDRATION", "name": "基础水光"}],
        "institutions": [],
    })


def insight() -> dict:
    return {
        "insight_type": "APPOINTMENT_INTENT",
        "customer_id": "C00128",
        "project_codes": ["BASIC_HYDRATION"],
        "mentioned_institution": None,
        "mentioned_time": None,
        "absolute_time": None,
        "evidence_message_ids": ["msg_001"],
        "confidence": 0.8,
        "proposed_actions": ["VERIFY_BALANCE"],
        "fact_status": "UNVERIFIED",
        "allow_financial_write": False,
        "reasoning_summary": "客户表达预约意向",
        "reply_draft": "为您人工核实",
        "followup_draft": None,
        "risk_flags": [],
    }


def test_openai_compatible_adapter_parses_strict_json():
    def handler(req: httpx.Request) -> httpx.Response:
        assert req.headers["Authorization"] == "Bearer test-key"
        body = json.loads(req.content)
        assert body["model"] == "test-model"
        assert "不可信" in body["messages"][0]["content"]
        assert body["response_format"] == {"type": "json_object"}
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(insight())}}]})

    extractor = OpenAICompatibleExtractor("https://model.example.test/v1", "test-key", "test-model", transport=httpx.MockTransport(handler))
    result, provider, model = extractor.extract(request())
    assert result.insight_type == "APPOINTMENT_INTENT"
    assert provider == "openai_compatible"
    assert model == "test-model"


def test_request_provider_config_takes_precedence_over_environment(monkeypatch):
    monkeypatch.setattr(settings, "ai_provider", "mock")
    monkeypatch.setattr(settings, "ai_base_url", "https://environment.example.test/v1")
    monkeypatch.setattr(settings, "ai_api_key", "environment-key")
    monkeypatch.setattr(settings, "ai_model", "environment-model")
    monkeypatch.setattr(settings, "ai_timeout_seconds", 30)
    data = request().model_dump(mode="json")
    data["provider_config"] = {
        "provider": "openai_compatible",
        "base_url": "https://request.example.test/v1/",
        "api_key": "request-secret-key",
        "model": "request-model",
        "timeout_seconds": 7.5,
    }
    configured_request = ExtractionRequest.model_validate(data)

    def handler(req: httpx.Request) -> httpx.Response:
        assert str(req.url) == "https://request.example.test/v1/chat/completions"
        assert req.headers["Authorization"] == "Bearer request-secret-key"
        assert req.extensions["timeout"] == {"connect": 7.5, "read": 7.5, "write": 7.5, "pool": 7.5}
        body = json.loads(req.content)
        assert body["model"] == "request-model"
        serialized_prompt = json.dumps(body["messages"], ensure_ascii=False)
        assert "request-secret-key" not in serialized_prompt
        assert "provider_config" not in serialized_prompt
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(insight())}}]})

    extractor = get_extractor(configured_request)
    assert isinstance(extractor, OpenAICompatibleExtractor)
    extractor.transport = httpx.MockTransport(handler)
    result, provider, model = extractor.extract(configured_request)
    assert result.insight_type == "APPOINTMENT_INTENT"
    assert provider == "openai_compatible"
    assert model == "request-model"


def test_provider_config_is_optional_and_environment_remains_fallback():
    configured_request = request()
    assert configured_request.provider_config is None
    assert isinstance(get_extractor(configured_request), MockExtractor)
    assert isinstance(get_extractor(), MockExtractor)


def test_request_disabled_provider_takes_precedence_over_environment():
    data = request().model_dump(mode="json")
    data["provider_config"] = {"provider": "disabled"}
    with pytest.raises(AiExtractionError) as exc_info:
        get_extractor(ExtractionRequest.model_validate(data))
    assert exc_info.value.code == "AI_PROVIDER_DISABLED"


def test_openai_compatible_adapter_rejects_invalid_output():
    transport = httpx.MockTransport(lambda _: httpx.Response(200, json={"choices": [{"message": {"content": "not-json"}}]}))
    extractor = OpenAICompatibleExtractor("https://model.example.test/v1", "test-key", "test-model", transport=transport)
    with pytest.raises(AiExtractionError, match="结构或证据校验"):
        extractor.extract(request())


def test_evidence_requires_at_least_one_message():
    data = insight()
    data["evidence_message_ids"] = []
    with pytest.raises(ValidationError):
        Insight.model_validate(data)
    validate_evidence(Insight.model_validate(insight()), request())


@pytest.mark.parametrize("intent_type", ["RISK_EVENT", "REFUND_INTENT", "COMPLAINT", "SERVICE_COMPLETION_CLAIM"])
def test_real_adapter_rejects_high_risk_classification_without_review_evidence(intent_type):
    unsafe = insight()
    unsafe["insight_type"] = intent_type
    unsafe["risk_flags"] = []
    unsafe["proposed_actions"] = []
    transport = httpx.MockTransport(lambda _: httpx.Response(200, json={
        "choices": [{"message": {"content": json.dumps(unsafe)}}],
    }))
    extractor = OpenAICompatibleExtractor("https://model.example.test/v1", "test-key", "test-model", transport=transport)
    with pytest.raises(AiExtractionError, match="结构或证据校验"):
        extractor.extract(request())


def test_real_model_prompt_contains_complete_intent_contract():
    from app.services.prompt import SYSTEM_PROMPT

    for intent_type in (
        "PACKAGE_QUERY", "APPOINTMENT_INTENT", "VISIT_INTENT", "AFTERCARE_FEEDBACK",
        "REFUND_INTENT", "COMPLAINT", "REPURCHASE_INTENT", "FOLLOWUP_REQUIRED", "RISK_EVENT",
        "PRICE_INQUIRY", "SERVICE_EXPERIENCE", "SERVICE_COMPLETION_CLAIM", "GENERAL",
    ):
        assert intent_type in SYSTEM_PROMPT
    assert "高风险" in SYSTEM_PROMPT
