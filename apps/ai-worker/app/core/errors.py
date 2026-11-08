"""统一错误结构与 requestId 中间件。

错误 JSON 结构:{code, message, requestId, timestamp},timestamp 为 ISO8601 UTC。
requestId 规则:优先透传请求头 X-Request-Id,否则生成 uuid4;始终写入响应头。
"""
import uuid
from datetime import datetime, timezone

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

REQUEST_ID_HEADER = "X-Request-Id"


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def error_body(code: str, message: str, request_id: str) -> dict:
    return {
        "code": code,
        "message": message,
        "requestId": request_id,
        "timestamp": utc_now_iso(),
    }


def error_response(status_code: int, code: str, message: str, request_id: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content=error_body(code, message, request_id))


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid.uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers[REQUEST_ID_HEADER] = request_id
        return response


def get_request_id(request: Request) -> str:
    return getattr(request.state, "request_id", None) or str(uuid.uuid4())
