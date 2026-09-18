"""GitHub 활동을 경험과 STAR로 변환하는 분석 API."""

from fastapi import APIRouter, Depends

from schemas.analysis import (
    ExperienceGroupingRequest,
    ExperienceGroupingResponse,
    StarAnalysisRequest,
    StarAnalysisResponse,
)
from services.analysis_pipeline import AnalysisPipeline

router = APIRouter(prefix="/analysis", tags=["analysis"])


def get_analysis_pipeline() -> AnalysisPipeline:
    """분석 파이프라인 의존성을 제공한다."""
    return AnalysisPipeline()


@router.post("/groups", response_model=ExperienceGroupingResponse)
async def group_experiences(
    request: ExperienceGroupingRequest,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
) -> ExperienceGroupingResponse:
    """수집된 커밋을 선별하고 기능 단위 경험 후보로 묶는다."""
    return await pipeline.group_experiences(request)


@router.post("/star", response_model=StarAnalysisResponse)
async def analyze_star(
    request: StarAnalysisRequest,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
) -> StarAnalysisResponse:
    """사용자가 확정한 경험의 diff를 분석해 STAR를 생성한다."""
    return await pipeline.analyze_star(request)
