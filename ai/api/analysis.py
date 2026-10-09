"""GitHub 활동을 경험과 STAR로 변환하는 내부 분석 API."""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.openapi import INVALID_PAYLOAD_RESPONSE
from schemas.analysis import (
    ExperienceGroupingRequest,
    ExperienceGroupingResponse,
    StarAnalysisRequest,
    StarAnalysisResponse,
)
from schemas.common import Envelope, ErrorDetail, ErrorEnvelope, Meta
from services.analysis_pipeline import AnalysisPipeline
from services.grouping_llm import GroupingLlmConfigError, GroupingLlmError

router = APIRouter(
    prefix="/internal/analysis", tags=["analysis"],
    responses={
        400: INVALID_PAYLOAD_RESPONSE,
        500: {
            "description": "분석 처리 중 내부 오류 또는 LLM 호출 실패",
            "content": {"text/plain": {"schema": {"type": "string"}, "example": "Internal Server Error"}},
        },
    },
)


def get_analysis_pipeline() -> AnalysisPipeline:
    """분석 파이프라인 의존성을 제공한다."""
    return AnalysisPipeline()


@router.post(
    "/groups", response_model=Envelope[ExperienceGroupingResponse],
    responses={
        503: {
            "model": ErrorEnvelope,
            "description": (
                "LLM_UNAVAILABLE: 그룹화 LLM 호출·출력 검증 실패(retryable=true) "
                "또는 LLM 설정 누락(retryable=false)."
            ),
        },
    },
    summary="A 경험 후보 그룹화",
    description="수집된 커밋을 선별하고 PR·Issue 연결 및 LLM 분석으로 경험 후보를 묶습니다.",
)
async def group_experiences(
    request: ExperienceGroupingRequest,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
) -> Envelope[ExperienceGroupingResponse]:
    """수집된 커밋을 선별하고 기능 단위 경험 후보로 묶는다."""
    started = time.perf_counter()
    try:
        result = await pipeline.group_experiences(request)
    except GroupingLlmConfigError:
        return _llm_unavailable(started, "그룹화 LLM 설정이 없어 분석할 수 없습니다.", False)
    except GroupingLlmError:
        return _llm_unavailable(started, "그룹화 LLM 호출에 실패했습니다.", True)
    processing_ms = int((time.perf_counter() - started) * 1000)
    return Envelope(
        success=True,
        data=result,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=processing_ms),
        error=None,
    )


@router.post(
    "/star", response_model=Envelope[StarAnalysisResponse],
    summary="A 확정 경험의 STAR 생성 (미구현)",
    description="선택 후보의 diff 근거로 STAR를 생성하는 계약입니다. 기본 서비스는 미구현으로 500을 반환합니다. 200 스키마는 구현 대상 계약입니다.",
)
async def analyze_star(
    request: StarAnalysisRequest,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
) -> Envelope[StarAnalysisResponse]:
    """사용자가 확정한 경험의 diff를 분석해 STAR를 생성한다."""
    started = time.perf_counter()
    result = await pipeline.analyze_star(request)
    processing_ms = int((time.perf_counter() - started) * 1000)
    return Envelope(
        success=True,
        data=result,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=processing_ms),
        error=None,
    )


def _llm_unavailable(started: float, message: str, retryable: bool) -> JSONResponse:
    """LLM 실패를 공통 에러 봉투로 반환한다. 내부 예외 문구는 응답에 싣지 않는다."""
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(
            model=None,
            tool_calls_made=0,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=ErrorDetail(code="LLM_UNAVAILABLE", message=message, retryable=retryable),
    )
    return JSONResponse(status_code=503, content=jsonable_encoder(envelope))
