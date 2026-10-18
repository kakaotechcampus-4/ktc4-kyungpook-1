"""Gitory AI 서버(A/B 파트) FastAPI 진입점.

명세서(gitory_api_spec_v2) 0-1 기준 AI 서버는 stateless로 판단·생성만
담당하고 저장·검증은 백엔드가 한다. 이 파일은 라우터 등록과 공통 에러
봉투 변환(0-3)만 담당한다.
"""

from __future__ import annotations

import logging
import os

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from api.collection import router as collection_router
from api.analysis import router as analysis_router
from api.interviews import router as interviews_router
from api.health import SERVICE_VERSION, router as health_router
from api.job_postings import router as job_postings_router
from api.matching import router as matching_router
from schemas.common import Envelope, ErrorDetail, Meta

logger = logging.getLogger(__name__)

MAX_LOGGED_VALIDATION_ERRORS = 5


async def handle_request_validation_error(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """Pydantic 요청 검증 실패를 공통 에러 봉투(INVALID_PAYLOAD, 0-3/0-4)로 변환한다.

    틀린 입력값에는 요청 본문 조각이 실릴 수 있어 칸의 위치와 종류만 최대 5개 남긴다.
    """
    problems = ", ".join(
        f"{'.'.join(str(part) for part in error['loc'])}:{error['type']}"
        for error in exc.errors()[:MAX_LOGGED_VALIDATION_ERRORS]
    )
    logger.warning("AI 요청 검증 실패: path=%s errors=%s", request.url.path, problems)
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


async def handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
    """처리하지 못한 예외를 INTERNAL_ERROR 봉투 500으로 바꾼다.

    traceback은 uvicorn이 이미 남기므로 경로와 예외 종류만 한 줄로 남긴다.
    """
    logger.error("AI 처리 못 한 예외: path=%s exc=%s", request.url.path, type(exc).__name__)
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=0),
        error=ErrorDetail(
            code="INTERNAL_ERROR",
            message="AI 내부 오류로 처리하지 못했습니다.",
            retryable=False,
        ),
    )
    return JSONResponse(status_code=500, content=jsonable_encoder(envelope))


def configure_logging() -> None:
    """레벨과 로거 이름이 보이게 루트 로거를 한 번 설정하고, 이미 설정돼 있으면 건드리지 않는다."""
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s")


def create_app(*, docs_enabled: bool | None = None) -> FastAPI:
    """실행 앱과 오프라인 문서 export가 동일한 라우터·스키마를 사용한다."""
    configure_logging()
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
            "**현재 구현 범위:** GitHub 수집, A 경험 그룹화·diff 근거·STAR 초안, 템플릿/규칙 기반 B-1/B-2, "
            "공고 조건 추출과 매칭 색인·검색이 구현되어 있습니다. LLM 설정이 없으면 LLM을 쓰는 API만 503으로 끝납니다. "
            "`/health`는 프로세스 생존 확인이며 LLM 또는 A 기능 readiness 검사가 아닙니다. "
            "이 API는 내부 서버 간 호출용이고 인터넷에 공개하는 인증 API가 아닙니다."
        ),
        openapi_tags=[
            {"name": "health", "description": "외부 의존성을 호출하지 않는 프로세스 생존 확인"},
            {"name": "collection", "description": "A-1 GitHub 활동 및 선택 후보 diff 수집. X-GitHub-Token 필수"},
            {"name": "analysis", "description": "A 경험 후보 그룹화·diff 근거·STAR 초안 생성"},
            {"name": "interview-turns", "description": "B-1 질문 생성 / B-2 답변 평가. 현재 템플릿·규칙 기반이며 저장은 Spring 담당"},
            {"name": "job-postings", "description": "공고 상세 본문에서 원문 인용이 있는 직무 조건 추출"},
            {"name": "matching", "description": "공고 조건 인덱싱과 확정 STAR 기반 직무 검색"},
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
    application.include_router(job_postings_router)
    application.include_router(matching_router)
    application.add_exception_handler(
        RequestValidationError, handle_request_validation_error
    )
    application.add_exception_handler(Exception, handle_unexpected_error)

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
