from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ChatMessage(StrictModel):
    id: str = Field(min_length=1, max_length=128)
    sender_type: Literal["CUSTOMER", "EMPLOYEE"]
    sent_at: datetime
    text: str = Field(min_length=1, max_length=4000)


class ProjectOption(StrictModel):
    code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z0-9_\-]+$")
    name: str = Field(min_length=1, max_length=128)


class InstitutionOption(StrictModel):
    id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=160)


class AiProviderConfig(StrictModel):
    provider: Literal["disabled", "mock", "openai_compatible"]
    base_url: str = ""
    api_key: str = ""
    model: str = ""
    timeout_seconds: float = Field(default=30, gt=0)


class ExtractionRequest(StrictModel):
    job_id: str = Field(min_length=1, max_length=64)
    customer_id: str = Field(min_length=1, max_length=64)
    messages: list[ChatMessage] = Field(min_length=1, max_length=50)
    projects: list[ProjectOption] = Field(default_factory=list, max_length=100)
    institutions: list[InstitutionOption] = Field(default_factory=list, max_length=100)
    provider_config: AiProviderConfig | None = None

    @model_validator(mode="after")
    def validate_context(self):
        ids = [message.id for message in self.messages]
        if len(ids) != len(set(ids)):
            raise ValueError("消息 ID 不能重复")
        if sum(len(message.text) for message in self.messages) > 30000:
            raise ValueError("聊天上下文不能超过 30000 字")
        return self


class FollowupDraft(StrictModel):
    title: str = Field(min_length=1, max_length=160)
    description: str = Field(min_length=1, max_length=2000)
    due_at: datetime | None = None


InsightType = Literal[
    "APPOINTMENT_INTENT", "PRICE_INQUIRY", "REFUND_INTENT", "COMPLAINT",
    "REPURCHASE_INTENT", "SERVICE_EXPERIENCE", "SERVICE_COMPLETION_CLAIM", "GENERAL",
]
ProposedAction = Literal[
    "VERIFY_BALANCE", "ASK_PREFERRED_INSTITUTION", "CREATE_FOLLOWUP_DRAFT",
    "VERIFY_SERVICE_RECORD", "OFFER_HUMAN_SUPPORT", "VERIFY_PAYMENT_RECORD",
]
RiskFlag = Literal["COMPLAINT", "REFUND_REQUEST", "SERVICE_CLAIM", "URGENT"]


class Insight(StrictModel):
    insight_type: InsightType
    customer_id: str = Field(min_length=1, max_length=64)
    project_codes: list[str] = Field(default_factory=list, max_length=20)
    mentioned_institution: str | None = Field(default=None, max_length=160)
    mentioned_time: str | None = Field(default=None, max_length=128)
    absolute_time: datetime | None = None
    evidence_message_ids: list[str] = Field(min_length=1, max_length=50)
    confidence: float = Field(ge=0, le=1)
    proposed_actions: list[ProposedAction] = Field(default_factory=list, max_length=10)
    fact_status: Literal["UNVERIFIED"]
    allow_financial_write: Literal[False]
    reasoning_summary: str = Field(min_length=1, max_length=2000)
    reply_draft: str = Field(default="", max_length=2000)
    followup_draft: FollowupDraft | None = None
    risk_flags: list[RiskFlag] = Field(default_factory=list, max_length=10)

    @field_validator("evidence_message_ids", "project_codes", "proposed_actions", "risk_flags")
    @classmethod
    def unique_values(cls, values: list[str]) -> list[str]:
        if len(values) != len(set(values)):
            raise ValueError("列表值不能重复")
        return values


class ExtractionResponse(StrictModel):
    requestId: str
    provider: str
    model: str
    insight: Insight


class KnowledgeProviderConfig(StrictModel):
    provider: Literal["disabled", "mock", "dify"]
    base_url: str = Field(default="", max_length=1024)
    api_key: str = Field(default="", max_length=512)
    timeout_seconds: float = Field(default=30, gt=0, le=300)


class KnowledgeChatRequest(StrictModel):
    question: str = Field(min_length=1, max_length=2000)
    end_user_id: str = Field(min_length=1, max_length=128)
    conversation_id: str | None = Field(default=None, min_length=1, max_length=128)
    provider_config: KnowledgeProviderConfig


class KnowledgeCitation(StrictModel):
    document_id: str = Field(min_length=1, max_length=128)
    document_name: str = Field(default="", max_length=512)
    segment_id: str | None = Field(default=None, max_length=128)
    content: str = Field(min_length=1, max_length=20000)
    score: float | None = None


class KnowledgeResult(StrictModel):
    provider: Literal["mock", "dify"]
    answer: str = Field(min_length=1, max_length=50000)
    message_id: str = Field(min_length=1, max_length=128)
    conversation_id: str = Field(min_length=1, max_length=128)
    citations: list[KnowledgeCitation] = Field(default_factory=list, max_length=50)


class KnowledgeChatResponse(KnowledgeResult):
    requestId: str


class KnowledgeDatasetConfig(StrictModel):
    provider: Literal["disabled", "mock", "dify"]
    base_url: str = Field(default="", max_length=1024)
    api_key: str = Field(default="", max_length=512)
    dataset_id: str = Field(default="", max_length=128)
    timeout_seconds: float = Field(default=30, gt=0, le=300)


class KnowledgeDocumentMetadata(StrictModel):
    tenant_id: str = Field(min_length=1, max_length=64)
    material_id: str = Field(min_length=1, max_length=64)
    revision_id: str = Field(min_length=1, max_length=64)
    revision_no: int = Field(ge=1)
    category_code: str = Field(min_length=1, max_length=48)
    institution_id: str | None = Field(default=None, max_length=64)
    project_id: str | None = Field(default=None, max_length=64)
    visibility: Literal["STAFF_INTERNAL"]
    content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")


class KnowledgeDocumentSyncRequest(StrictModel):
    job_id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=1_048_576)
    metadata: KnowledgeDocumentMetadata
    dataset_config: KnowledgeDatasetConfig
    document_id: str | None = Field(default=None, min_length=1, max_length=128)


class KnowledgeDocumentStatusRequest(StrictModel):
    batch: str = Field(min_length=1, max_length=128)
    document_id: str = Field(min_length=1, max_length=128)
    dataset_config: KnowledgeDatasetConfig


class KnowledgeDocumentDisableRequest(StrictModel):
    document_id: str = Field(min_length=1, max_length=128)
    dataset_config: KnowledgeDatasetConfig


KnowledgeIndexingStatus = Literal["INDEXING", "AVAILABLE", "FAILED"]


class KnowledgeDocumentSyncResult(StrictModel):
    provider: Literal["mock", "dify"]
    document_id: str = Field(min_length=1, max_length=128)
    batch: str = Field(min_length=1, max_length=128)
    indexing_status: KnowledgeIndexingStatus
    error_masked: str | None = Field(default=None, max_length=500)


class KnowledgeDocumentSyncResponse(KnowledgeDocumentSyncResult):
    requestId: str


class KnowledgeDocumentStatusResponse(KnowledgeDocumentSyncResult):
    requestId: str


class KnowledgeDocumentDisableResult(StrictModel):
    provider: Literal["mock", "dify"]
    document_id: str = Field(min_length=1, max_length=128)
    disabled: Literal[True]


class KnowledgeDocumentDisableResponse(KnowledgeDocumentDisableResult):
    requestId: str
