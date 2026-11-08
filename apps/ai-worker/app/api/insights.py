import hmac

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.config import settings
from app.core.errors import error_response, get_request_id
from app.schemas import ExtractionRequest, ExtractionResponse
from app.services.extractor import AiExtractionError, get_extractor

router = APIRouter(prefix="/api/v1/insights", tags=["insights"])


@router.post("/extract", response_model=ExtractionResponse)
def extract_insight(body: ExtractionRequest, request: Request) -> ExtractionResponse | JSONResponse:
    request_id = get_request_id(request)
    if not settings.ai_internal_token or not hmac.compare_digest(request.headers.get("X-AI-Internal-Token", ""), settings.ai_internal_token):
        return error_response(401, "UNAUTHORIZED", "内部服务认证失败", request_id)
    try:
        insight, provider, model = get_extractor(body).extract(body)
        return ExtractionResponse(requestId=request_id, provider=provider, model=model, insight=insight)
    except AiExtractionError as exc:
        return error_response(exc.status_code, exc.code, exc.message, request_id)
    except ValueError:
        return error_response(502, "AI_OUTPUT_INVALID", "AI 输出未通过结构或证据校验", request_id)
