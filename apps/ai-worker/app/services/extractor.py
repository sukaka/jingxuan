import json
import re
from typing import Protocol

import httpx
from pydantic import ValidationError

from app.config import settings
from app.schemas import ExtractionRequest, FollowupDraft, Insight
from app.services.evidence import validate_evidence
from app.services.prompt import extraction_messages


class AiExtractionError(Exception):
    def __init__(self, code: str, message: str, status_code: int = 502):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


class Extractor(Protocol):
    def extract(self, request: ExtractionRequest) -> tuple[Insight, str, str]: ...


def get_extractor(request: ExtractionRequest | None = None) -> Extractor:
    config = request.provider_config if request else None
    provider = config.provider if config else settings.ai_provider
    if provider == "mock":
        return MockExtractor()
    if provider == "openai_compatible":
        if config:
            return OpenAICompatibleExtractor(
                config.base_url,
                config.api_key,
                config.model,
                timeout_seconds=config.timeout_seconds,
            )
        return OpenAICompatibleExtractor(settings.ai_base_url, settings.ai_api_key, settings.ai_model)
    raise AiExtractionError("AI_PROVIDER_DISABLED", "AI 模型服务未启用", 503)


class MockExtractor:
    def extract(self, request: ExtractionRequest) -> tuple[Insight, str, str]:
        safe_text = self._without_instructions("\n".join(message.text for message in request.messages))
        insight_type, summary, actions, risks = self._classify(safe_text)
        projects = [project.code for project in request.projects if project.name in safe_text or project.code in safe_text]
        institution = next((item.name for item in request.institutions if item.name in safe_text), None)
        mentioned_time = self._mentioned_time(safe_text)
        evidence = self._evidence_ids(request, insight_type)
        draft = None
        if insight_type != "GENERAL":
            title = "人工复核客户风险线索" if insight_type == "RISK_EVENT" else "人工核实客户沟通意向"
            if insight_type == "SERVICE_COMPLETION_CLAIM":
                title = "人工核实服务记录"
            draft = FollowupDraft(title=title, description=f"{summary}；请结合原始证据人工确认，不自动修改业务事实。")
        insight = Insight(
            insight_type=insight_type,
            customer_id=request.customer_id,
            project_codes=projects,
            mentioned_institution=institution,
            mentioned_time=mentioned_time,
            absolute_time=None,
            evidence_message_ids=evidence,
            confidence=0.72 if insight_type != "GENERAL" else 0.45,
            proposed_actions=actions,
            fact_status="UNVERIFIED",
            allow_financial_write=False,
            reasoning_summary=summary,
            reply_draft="已记录您的需求，将由专属管家进一步核实并回复。" if insight_type != "GENERAL" else "",
            followup_draft=draft,
            risk_flags=risks,
        )
        return validate_evidence(insight, request), "mock", "mock-rules-v2"

    @classmethod
    def _evidence_ids(cls, request: ExtractionRequest, insight_type: str) -> list[str]:
        matches = [message.id for message in request.messages
                   if cls._classify(cls._without_instructions(message.text))[0] == insight_type]
        if matches:
            return matches
        safe_customer = next((message.id for message in request.messages
                              if message.sender_type == "CUSTOMER" and cls._without_instructions(message.text)), None)
        return [safe_customer or request.messages[0].id]

    @staticmethod
    def _without_instructions(text: str) -> str:
        segments = re.split(r"[。！？!?；;\n]", text)
        forbidden = ("忽略", "调用接口", "修改余额", "修改会员余额", "确认退款", "确认支付", "正式核销", "执行命令")
        return "；".join(segment for segment in segments if not any(word in segment for word in forbidden))

    @staticmethod
    def _classify(text: str):
        if any(word in text for word in ("医疗事故", "报警", "律师", "起诉", "曝光", "监管", "12315", "感染", "发烧", "呼吸困难", "严重红肿", "严重不适", "过敏", "症状加重")):
            return "RISK_EVENT", "客户陈述包含需立即人工介入的风险线索，不代表医疗或责任结论", ["OFFER_HUMAN_SUPPORT", "CREATE_FOLLOWUP_DRAFT"], ["URGENT"]
        if any(word in text for word in ("我要退款", "申请退款", "想退款", "怎么退款", "退费", "退钱")):
            return "REFUND_INTENT", "客户表达退款意向，尚未核实订单与退款规则", ["VERIFY_PAYMENT_RECORD", "CREATE_FOLLOWUP_DRAFT"], ["REFUND_REQUEST"]
        if any(word in text for word in ("投诉", "态度差", "不满意", "失望", "差评", "服务差")):
            return "COMPLAINT", "客户表达投诉或服务不满，需主管人工核实", ["OFFER_HUMAN_SUPPORT", "CREATE_FOLLOWUP_DRAFT"], ["COMPLAINT", "URGENT"]
        if any(word in text for word in ("做完", "已做", "做了", "服务完")):
            return "SERVICE_COMPLETION_CLAIM", "客户陈述已完成服务，仅可作为待核实线索", ["VERIFY_SERVICE_RECORD", "CREATE_FOLLOWUP_DRAFT"], ["SERVICE_CLAIM"]
        if any(word in text for word in ("预约", "想约", "改约", "下周", "安排时间", "什么时候可以", "什么时候能", "想做")):
            actions = ["VERIFY_BALANCE", "CREATE_FOLLOWUP_DRAFT"]
            if "医院" not in text and "机构" not in text:
                actions.insert(1, "ASK_PREFERRED_INSTITUTION")
            return "APPOINTMENT_INTENT", "客户表达预约意向，时间与机构仍需人工确认", actions, []
        if any(word in text for word in ("到院", "到店", "来院", "面诊", "想去医院", "过去看看")):
            actions = ["CREATE_FOLLOWUP_DRAFT"]
            if "医院" not in text and "机构" not in text:
                actions.insert(0, "ASK_PREFERRED_INSTITUTION")
            return "VISIT_INTENT", "客户表达到院或面诊意向，实际接诊安排仍需人工确认", actions, []
        if any(word in text for word in ("套餐", "权益", "剩几次", "剩余几次", "还剩", "几次", "余额", "还能用", "可以用几次")):
            return "PACKAGE_QUERY", "客户查询套餐或项目权益，需读取真实权益账本后人工回复", ["VERIFY_BALANCE", "CREATE_FOLLOWUP_DRAFT"], []
        if any(word in text for word in ("多少钱", "价格", "费用", "优惠价", "怎么收费")):
            return "PRICE_INQUIRY", "客户询问价格，需按当前销售快照人工回复", ["CREATE_FOLLOWUP_DRAFT"], []
        if any(word in text for word in ("复购", "再买", "续卡", "续费", "再购买", "想再做")):
            return "REPURCHASE_INTENT", "客户表达复购意向，需人工核实适合的销售方案", ["VERIFY_BALANCE", "CREATE_FOLLOWUP_DRAFT"], []
        if any(word in text for word in ("术后", "护理", "恢复", "效果", "反馈", "不适", "红肿", "痒", "疼", "刺痛", "灼热")):
            return "AFTERCARE_FEEDBACK", "客户描述术后护理或服务后反馈，专业问题需转机构人员人工处理", ["OFFER_HUMAN_SUPPORT", "CREATE_FOLLOWUP_DRAFT"], []
        if any(word in text for word in ("体验", "服务感受")):
            return "SERVICE_EXPERIENCE", "客户描述服务体验，保留为待人工核实的反馈线索", ["OFFER_HUMAN_SUPPORT", "CREATE_FOLLOWUP_DRAFT"], []
        if any(word in text for word in ("回访", "联系我", "回复我", "回电话", "再联系", "稍后聊", "考虑考虑", "以后再说", "改天再聊")):
            return "FOLLOWUP_REQUIRED", "客户需要进一步回访或联系，仅生成人工跟进建议", ["CREATE_FOLLOWUP_DRAFT"], []
        return "GENERAL", "未识别出明确业务意向，仅保留人工阅读线索", [], []

    @staticmethod
    def _mentioned_time(text: str) -> str | None:
        match = re.search(r"下周[一二三四五六日天]|本周[一二三四五六日天]|明天|后天|今天|今晚", text)
        return match.group(0) if match else None


class OpenAICompatibleExtractor:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        transport: httpx.BaseTransport | None = None,
        timeout_seconds: float | None = None,
    ):
        if not base_url or not api_key or not model:
            raise AiExtractionError("AI_PROVIDER_NOT_CONFIGURED", "AI 模型供应商配置不完整", 503)
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.transport = transport
        self.timeout_seconds = settings.ai_timeout_seconds if timeout_seconds is None else timeout_seconds

    def extract(self, request: ExtractionRequest) -> tuple[Insight, str, str]:
        try:
            with httpx.Client(timeout=self.timeout_seconds, transport=self.transport) as client:
                response = client.post(
                    f"{self.base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {self.api_key}"},
                    json={"model": self.model, "messages": extraction_messages(request), "response_format": {"type": "json_object"}, "temperature": 0},
                )
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
            insight = Insight.model_validate(json.loads(content))
            return validate_evidence(insight, request), "openai_compatible", self.model
        except (httpx.HTTPError, KeyError, IndexError, TypeError) as exc:
            raise AiExtractionError("AI_PROVIDER_FAILED", "AI 模型服务暂时不可用") from exc
        except (json.JSONDecodeError, ValidationError, ValueError) as exc:
            raise AiExtractionError("AI_OUTPUT_INVALID", "AI 输出未通过结构或证据校验") from exc
