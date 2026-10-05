"""GitHub 활동을 경험과 STAR로 변환하는 내부 분석 API."""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends

from api.openapi import INVALID_PAYLOAD_RESPONSE
from schemas.analysis import (
    ExperienceGroupingRequest,
    ExperienceGroupingResponse,
    StarAnalysisRequest,
    StarAnalysisResponse,
)
from schemas.common import Envelope, Meta
from services.analysis_pipeline import AnalysisPipeline

router = APIRouter(
    prefix="/internal/analysis", tags=["analysis"],
    responses={
        400: INVALID_PAYLOAD_RESPONSE,
        500: {
            "description": "기본 AnalysisPipeline은 아직 미구현입니다. 현재 유효한 요청도 500으로 실패합니다.",
            "content": {"text/plain": {"schema": {"type": "string"}, "example": "Internal Server Error"}},
        },
    },
)


def get_analysis_pipeline() -> AnalysisPipeline:
    """분석 파이프라인 의존성을 제공한다."""
    return AnalysisPipeline()


@router.post(
    "/groups", response_model=Envelope[ExperienceGroupingResponse],
    summary="A 경험 후보 그룹화 (미구현)",
    description="수집된 커밋을 선별하고 경험 후보로 묶는 계약입니다. 기본 서비스는 미구현으로 500을 반환합니다. 200 스키마는 구현 대상 계약입니다.",
)
async def group_experiences(
    request: ExperienceGroupingRequest,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
) -> Envelope[ExperienceGroupingResponse]:
    """수집된 커밋을 선별하고 기능 단위 경험 후보로 묶는다."""
    started = time.perf_counter()
    result = await pipeline.group_experiences(request)
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
