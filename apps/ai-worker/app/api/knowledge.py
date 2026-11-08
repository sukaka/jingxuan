import hmac

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.config import settings
from app.core.errors import error_response, get_request_id
from app.schemas import (
    KnowledgeChatRequest,
    KnowledgeChatResponse,
    KnowledgeDocumentDisableRequest,
    KnowledgeDocumentDisableResponse,
    KnowledgeDocumentStatusRequest,
    KnowledgeDocumentStatusResponse,
    KnowledgeDocumentSyncRequest,
    KnowledgeDocumentSyncResponse,
)
from app.services.knowledge import AiKnowledgeError, get_knowledge_adapter
from app.services.knowledge_dataset import get_dataset_adapter

router = APIRouter(prefix="/api/v1/knowledge", tags=["knowledge"])


def _internal_auth_error(request: Request, request_id: str) -> JSONResponse | None:
    if not settings.ai_internal_token or not hmac.compare_digest(
        request.headers.get("X-AI-Internal-Token", ""), settings.ai_internal_token
    ):
        return error_response(401, "UNAUTHORIZED", "内部服务认证失败", request_id)
    return None


@router.post("/chat", response_model=KnowledgeChatResponse)
def chat(body: KnowledgeChatRequest, request: Request) -> KnowledgeChatResponse | JSONResponse:
    request_id = get_request_id(request)
    auth_error = _internal_auth_error(request, request_id)
    if auth_error is not None:
        return auth_error
    try:
        result = get_knowledge_adapter(body).chat(body)
        return KnowledgeChatResponse(requestId=request_id, **result.model_dump())
    except AiKnowledgeError as exc:
        return error_response(exc.status_code, exc.code, exc.message, request_id)
    except ValueError:
        return error_response(502, "AI_OUTPUT_INVALID", "Dify 输出未通过结构校验", request_id)


@router.post("/documents/sync", response_model=KnowledgeDocumentSyncResponse)
def sync_document(body: KnowledgeDocumentSyncRequest, request: Request) -> KnowledgeDocumentSyncResponse | JSONResponse:
    request_id = get_request_id(request)
    auth_error = _internal_auth_error(request, request_id)
    if auth_error is not None:
        return auth_error
    try:
        result = get_dataset_adapter(body.dataset_config).sync(body)
        return KnowledgeDocumentSyncResponse(requestId=request_id, **result.model_dump())
    except AiKnowledgeError as exc:
        return error_response(exc.status_code, exc.code, exc.message, request_id)
    except ValueError:
        return error_response(502, "AI_OUTPUT_INVALID", "Dify 文档响应未通过结构校验", request_id)


@router.post("/documents/status", response_model=KnowledgeDocumentStatusResponse)
def document_status(body: KnowledgeDocumentStatusRequest, request: Request) -> KnowledgeDocumentStatusResponse | JSONResponse:
    request_id = get_request_id(request)
    auth_error = _internal_auth_error(request, request_id)
    if auth_error is not None:
        return auth_error
    try:
        result = get_dataset_adapter(body.dataset_config).status(body)
        return KnowledgeDocumentStatusResponse(requestId=request_id, **result.model_dump())
    except AiKnowledgeError as exc:
        return error_response(exc.status_code, exc.code, exc.message, request_id)
    except ValueError:
        return error_response(502, "AI_OUTPUT_INVALID", "Dify 索引响应未通过结构校验", request_id)


@router.post("/documents/disable", response_model=KnowledgeDocumentDisableResponse)
def disable_document(body: KnowledgeDocumentDisableRequest, request: Request) -> KnowledgeDocumentDisableResponse | JSONResponse:
    request_id = get_request_id(request)
    auth_error = _internal_auth_error(request, request_id)
    if auth_error is not None:
        return auth_error
    try:
        result = get_dataset_adapter(body.dataset_config).disable(body)
        return KnowledgeDocumentDisableResponse(requestId=request_id, **result.model_dump())
    except AiKnowledgeError as exc:
        return error_response(exc.status_code, exc.code, exc.message, request_id)
    except ValueError:
        return error_response(502, "AI_OUTPUT_INVALID", "Dify 停用响应未通过结构校验", request_id)
