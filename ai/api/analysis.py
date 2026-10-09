"""GitHub 활동을 경험과 STAR로 변환하는 내부 분석 API."""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends, Header
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.collection import get_repository_activity_port
from api.openapi import COLLECTION_ERROR_RESPONSES, INVALID_PAYLOAD_RESPONSE, LLM_UNAVAILABLE_RESPONSE
from ports.repository_activity import RepositoryActivityPort
from schemas.analysis import (
    DiffEvidenceRequest,
    DiffEvidenceResult,
    ExperienceGroupingRequest,
    ExperienceGroupingResponse,
    StarAnalysisRequest,
    StarAnalysisResponse,
)
from schemas.common import Envelope, ErrorDetail, ErrorEnvelope, Meta
from services.analysis_pipeline import AnalysisPipeline
from services.diff_analyzer import DiffAnalysisError
from services.github_collector import GithubApiError, GithubResourceNotFound
from services.grouping_llm import GroupingLlmConfigError, GroupingLlmError
from services.llm_settings import LLMConfigError
from services.star_generator import StarGenerationError

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
    "/diff-evidence", response_model=Envelope[DiffEvidenceResult],
    summary="A 선택 후보의 커밋별 diff 근거 생성",
    description=(
        "선택된 후보의 diff를 GitHub에서 다시 조회해 커밋별 변경 요약·기술 포인트로 정리합니다. "
        "응답의 evidence는 그대로 /internal/analysis/star 요청의 evidence로 넘깁니다. "
        "원본 patch는 AI 안에서만 쓰고 응답·로그·DB에 남기지 않습니다. 비밀값은 모델로 보내기 전에 가립니다. "
        "diff 수집이 상한이나 rate limit으로 일부만 되면 200의 partial=true로 표시합니다. "
        "GitHub 토큰은 헤더로만 전달합니다."
    ),
    responses={**COLLECTION_ERROR_RESPONSES, 503: LLM_UNAVAILABLE_RESPONSE},
)
async def collect_diff_evidence(
    request: DiffEvidenceRequest,
    x_github_token: str = Header(
        ..., alias="X-GitHub-Token", min_length=1,
        description="Spring이 호출 중에만 전달하는 GitHub OAuth 토큰. 요청 본문이나 문서 예시에 넣지 않습니다.",
    ),
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
    activity_port: RepositoryActivityPort = Depends(get_repository_activity_port),
):
    """선택 후보의 diff를 읽어 STAR 입력용 커밋별 근거로 압축한다."""
    started = time.perf_counter()
    try:
        execution = await pipeline.collect_diff_evidence(request, x_github_token, activity_port)
    except GithubResourceNotFound:
        return _error(started, 404, "RESOURCE_NOT_FOUND", "저장소·PR·commit이 없거나 현재 권한으로 읽을 수 없습니다.", False)
    except GithubApiError:
        return _error(started, 502, "GITHUB_API_ERROR", "선택된 후보의 GitHub diff를 수집하지 못했습니다.", True)
    except LLMConfigError:
        return _error(started, 503, "LLM_UNAVAILABLE", "diff 분석 LLM 설정이 없어 분석할 수 없습니다.", False)
    except DiffAnalysisError:
        return _error(started, 503, "LLM_UNAVAILABLE", "diff 분석 LLM 호출에 실패했습니다.", True)

    return Envelope(
        success=True,
        data=execution.result,
        meta=Meta(model=None, tool_calls_made=execution.api_calls, processing_ms=_elapsed_ms(started)),
        error=None,
    )


@router.post(
    "/star", response_model=Envelope[StarAnalysisResponse],
    summary="A 확정 경험의 STAR 초안 생성",
    description=(
        "/internal/analysis/diff-evidence가 만든 커밋별 근거로 인터뷰 전 STAR 초안을 생성합니다. "
        "근거 커밋이 없거나 입력에 없는 수치를 쓴 문장은 비우고 removed_claims에 남깁니다. "
        "비운 칸은 missing_fields로 반환하며 B 인터뷰가 이 칸을 질문합니다."
    ),
    responses={503: LLM_UNAVAILABLE_RESPONSE},
)
async def analyze_star(
    request: StarAnalysisRequest,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
):
    """사용자가 확정한 경험의 diff 근거로 STAR 초안을 생성한다."""
    started = time.perf_counter()
    try:
        result = await pipeline.analyze_star(request)
    except LLMConfigError:
        return _error(started, 503, "LLM_UNAVAILABLE", "STAR LLM 설정이 없어 분석할 수 없습니다.", False)
    except StarGenerationError:
        return _error(started, 503, "LLM_UNAVAILABLE", "STAR LLM 호출에 실패했습니다.", True)

    return Envelope(
        success=True,
        data=result,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=_elapsed_ms(started)),
        error=None,
    )


def _llm_unavailable(started: float, message: str, retryable: bool) -> JSONResponse:
    """LLM 실패를 공통 에러 봉투로 반환한다. 내부 예외 문구는 응답에 싣지 않는다."""
    return _error(started, 503, "LLM_UNAVAILABLE", message, retryable)


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _error(started: float, status_code: int, code: str, message: str, retryable: bool) -> JSONResponse:
    """실패를 공통 에러 봉투로 반환한다. 내부 예외 문구는 응답에 싣지 않는다."""
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=_elapsed_ms(started)),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
