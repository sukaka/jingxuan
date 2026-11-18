import hmac

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.config import settings
from app.core.errors import error_response, get_request_id
from app.schemas import ReplyGenerationRequest, ReplyGenerationResponse
from app.services.reply import PROMPT_VERSION, AiReplyError, get_generator

router = APIRouter(prefix="/api/v1/replies", tags=["replies"])


@router.post("/generate", response_model=ReplyGenerationResponse)
def generate_reply(body: ReplyGenerationRequest, request: Request) -> ReplyGenerationResponse | JSONResponse:
    request_id = get_request_id(request)
    if not settings.ai_internal_token or not hmac.compare_digest(request.headers.get("X-AI-Internal-Token", ""), settings.ai_internal_token):
        return error_response(401, "UNAUTHORIZED", "内部服务认证失败", request_id)
    try:
        suggestion, provider, model = get_generator(body).generate(body)
        return ReplyGenerationResponse(
            requestId=request_id, provider=provider, model=model, prompt_version=PROMPT_VERSION, suggestion=suggestion,
        )
    except AiReplyError as exc:
        return error_response(exc.status_code, exc.code, exc.message, request_id)
    except ValueError:
        return error_response(502, "AI_OUTPUT_INVALID", "AI 回复未通过结构、场景或事实校验", request_id)
