"""FastAPI 应用工厂。"""
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api import health, insights, knowledge
from app.core.errors import RequestIdMiddleware, error_response, get_request_id

VERSION = "0.1.0"


def create_app() -> FastAPI:
    app = FastAPI(title="ai-worker", version=VERSION)
    app.add_middleware(RequestIdMiddleware)

    app.include_router(health.router)
    app.include_router(insights.router)
    app.include_router(knowledge.router)

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException):
        return error_response(
            status_code=exc.status_code,
            code="HTTP_ERROR",
            message=str(exc.detail),
            request_id=get_request_id(request),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError):
        return error_response(422, "VALIDATION_FAILED", "请求结构或上下文大小不符合要求", get_request_id(request))

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception):
        return error_response(
            status_code=500,
            code="INTERNAL_ERROR",
            message="Internal server error",
            request_id=get_request_id(request),
        )

    return app


app = create_app()
