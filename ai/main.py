"""Gitory AI 서버(A/B 파트) FastAPI 진입점.

명세서(gitory_api_spec_v2) 0-1 기준 AI 서버는 stateless로 판단·생성만
담당하고 저장·검증은 백엔드가 한다. 이 파일은 라우터 등록과 공통 에러
봉투 변환(0-3)만 담당한다.
"""

from __future__ import annotations

import os

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from api.collection import router as collection_router
from api.analysis import router as analysis_router
from api.interviews import router as interviews_router
from api.health import SERVICE_VERSION, router as health_router
from schemas.common import Envelope, ErrorDetail, Meta

async def handle_request_validation_error(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """Pydantic 요청 검증 실패를 공통 에러 봉투(INVALID_PAYLOAD, 0-3/0-4)로 변환한다."""
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=0),
        error=ErrorDetail(
            code="INVALID_PAYLOAD",
            message="요청 스키마가 명세와 일치하지 않습니다.",
            retryable=False,
        ),
    )
    return JSONResponse(status_code=400, content=jsonable_encoder(envelope))


def create_app(*, docs_enabled: bool | None = None) -> FastAPI:
    """실행 앱과 오프라인 문서 export가 동일한 라우터·스키마를 사용한다."""
    if docs_enabled is None:
        docs_enabled = os.getenv("AI_DOCS_ENABLED", "true").strip().lower() in {
            "true", "1", "yes", "on"
        }

    application = FastAPI(
        title="Gitory AI Internal API",
        version=SERVICE_VERSION,
        summary="Spring 백엔드에서 호출하는 stateless 수집·분석·되묻기 API",
        description=(
            "AI는 판단·생성만 수행하며 인증·소유권 검증·영속 저장은 Spring이 담당합니다. "
            "`/internal/*` 응답은 공통 `Envelope`이고 스키마 검증 실패는 422 대신 400입니다.\n\n"
            "**현재 구현 범위:** GitHub 수집과 템플릿/규칙 기반 B-1/B-2가 구현되어 있습니다. "
            "A 경험 그룹화·STAR 생성은 아직 미구현이며, 기본 파이프라인 호출은 500으로 끝납니다. "
            "`/health`는 프로세스 생존 확인이며 LLM 또는 A 기능 readiness 검사가 아닙니다. "
            "이 API는 내부 서버 간 호출용이고 인터넷에 공개하는 인증 API가 아닙니다."
        ),
        openapi_tags=[
            {"name": "health", "description": "외부 의존성을 호출하지 않는 프로세스 생존 확인"},
            {"name": "collection", "description": "A-1 GitHub 활동 및 선택 후보 diff 수집. X-GitHub-Token 필수"},
            {"name": "analysis", "description": "A 경험 후보 그룹화·STAR 생성 계약. 현재 기본 구현은 미완성"},
            {"name": "interview-turns", "description": "B-1 질문 생성 / B-2 답변 평가. 현재 템플릿·규칙 기반이며 저장은 Spring 담당"},
        ],
        openapi_url="/openapi.json" if docs_enabled else None,
        docs_url="/docs" if docs_enabled else None,
        redoc_url="/redoc" if docs_enabled else None,
        swagger_ui_parameters={"persistAuthorization": False, "displayRequestDuration": True},
    )
    application.include_router(health_router)
    application.include_router(analysis_router)
    application.include_router(collection_router)
    application.include_router(interviews_router)
    application.add_exception_handler(
        RequestValidationError, handle_request_validation_error
    )

    default_openapi = application.openapi

    def custom_openapi() -> dict:
        schema = default_openapi()
        # FastAPI 자동 422 문서는 위 핸들러가 실제 반환하는 400과 다르다.
        for path, operations in schema["paths"].items():
            if path.startswith("/internal/"):
                for operation in operations.values():
                    if isinstance(operation, dict) and "responses" in operation:
                        operation["responses"].pop("422", None)
        return schema

    application.openapi = custom_openapi
    return application


app = create_app()
