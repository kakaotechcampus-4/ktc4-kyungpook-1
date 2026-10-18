"""GitHub 활동을 경험과 STAR로 변환하는 내부 분석 API."""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Awaitable

from fastapi import APIRouter, Depends, Header, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.collection import get_repository_activity_port
from api.failure_log import log_failure
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

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal/analysis", tags=["analysis"],
    responses={
        400: INVALID_PAYLOAD_RESPONSE,
        500: {
            "model": ErrorEnvelope,
            "description": "INTERNAL_ERROR: 처리하지 못한 내부 오류. 재시도 불가.",
        },
    },
)


#: 그룹화 요청 전체 마감(초). Spring 대기(600초)보다 짧게 두어 Spring이 먼저 끊기 전에
#: retryable 503으로 답한다. LLM 호출이 차례로 나가 후보가 많으면 최악 지연이 이보다 길다.
GROUPING_DEADLINE_SECONDS = 540.0
#: 호출자 연결 끊김을 확인하는 간격(초).
DISCONNECT_POLL_SECONDS = 1.0


class _ClientDisconnected(Exception):
    """호출자가 응답을 기다리지 않고 연결을 끊은 경우."""


def get_analysis_pipeline() -> AnalysisPipeline:
    """분석 파이프라인 의존성을 제공한다."""
    return AnalysisPipeline()


@router.post(
    "/groups", response_model=Envelope[ExperienceGroupingResponse],
    responses={
        503: {
            "model": ErrorEnvelope,
            "description": (
                "LLM_UNAVAILABLE: 그룹화 LLM 호출·출력 검증 실패 또는 요청 마감(540초) "
                "초과(retryable=true), LLM 설정 누락(retryable=false)."
            ),
        },
    },
    summary="A 경험 후보 그룹화",
    description="수집된 커밋을 선별하고 PR·Issue 연결 및 LLM 분석으로 경험 후보를 묶습니다.",
)
async def group_experiences(
    request: ExperienceGroupingRequest,
    http_request: Request,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
) -> Envelope[ExperienceGroupingResponse]:
    """수집된 커밋을 선별하고 기능 단위 경험 후보로 묶는다."""
    started = time.perf_counter()
    try:
        result = await _run_until_deadline_or_disconnect(
            pipeline.group_experiences(request), http_request
        )
    except asyncio.TimeoutError:
        logger.warning("경험 그룹화 실패: 요청 마감 %s초 초과", GROUPING_DEADLINE_SECONDS)
        return _llm_unavailable(started, "그룹화 처리 시간이 초과되었습니다.", True)
    except _ClientDisconnected:
        # 받을 사람이 없지만 남은 LLM 호출은 이미 취소했다. 응답은 형식만 맞춘다.
        return _llm_unavailable(started, "호출자가 연결을 끊었습니다.", True)
    except GroupingLlmConfigError:
        logger.warning("경험 그룹화 실패: LLM 설정 없음", exc_info=True)
        return _llm_unavailable(started, "그룹화 LLM 설정이 없어 분석할 수 없습니다.", False)
    except GroupingLlmError:
        # 응답에는 내부 예외 문구를 싣지 않으므로, 운영에서 원인을 찾을 수 있게 여기서만 남긴다.
        logger.warning("경험 그룹화 실패: LLM 호출·출력 검증", exc_info=True)
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
        "요청자 본인 커밋(commit_shas, 필수)의 diff를 GitHub에서 다시 조회해 커밋별 변경 요약·기술 포인트로 정리합니다. "
        "응답의 evidence는 그대로 /internal/analysis/star 요청의 evidence로 넘깁니다. "
        "원본 patch는 AI 안에서만 쓰고 응답·로그·DB에 남기지 않습니다. 비밀값은 모델로 보내기 전에 가립니다. "
        "요청 전체는 240초 안에 끝나며, 시간·호출 수 상한에 닿았거나 patch를 하나도 보내지 못한 커밋은 "
        "커밋 메시지 요약(summary_source=COMMIT_MESSAGE)으로 둡니다. "
        "요청한 커밋이 근거에서 빠졌을 때만 partial=true이고 missing_commit_shas에 남깁니다. "
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
    except GithubResourceNotFound as exc:
        _log_diff_evidence_failure(request, "RESOURCE_NOT_FOUND", False, exc, started)
        return _error(started, 404, "RESOURCE_NOT_FOUND", "저장소·PR·commit이 없거나 현재 권한으로 읽을 수 없습니다.", False)
    except GithubApiError as exc:
        retryable = exc.secondary_rate_limited or exc.status_code not in _GITHUB_PERMANENT_STATUSES
        _log_diff_evidence_failure(request, "GITHUB_API_ERROR", retryable, exc, started)
        return _error(
            started, 502, "GITHUB_API_ERROR", "선택된 후보의 GitHub diff를 수집하지 못했습니다.", retryable,
        )
    except LLMConfigError as exc:
        _log_diff_evidence_failure(request, "LLM_UNAVAILABLE", False, exc, started)
        return _error(started, 503, "LLM_UNAVAILABLE", "diff 분석 LLM 설정이 없어 분석할 수 없습니다.", False)
    except DiffAnalysisError as exc:
        _log_diff_evidence_failure(request, "LLM_UNAVAILABLE", exc.retryable, exc, started)
        return _error(started, 503, "LLM_UNAVAILABLE", "diff 분석 LLM 호출에 실패했습니다.", exc.retryable)

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
        "/internal/analysis/diff-evidence가 만든 커밋별 근거(1개 이상)로 인터뷰 전 STAR 초안을 생성합니다. "
        "근거 커밋과 확인 답변이 없는 문장은 비우고 removed_claims에 남깁니다. "
        "비운 칸은 insufficient_reason_code(NO_EVIDENCE·TIMEOUT)와 함께 missing_fields로 반환하며 "
        "B 인터뷰가 이 칸을 질문합니다. 150초 안에 초안을 받지 못하면 네 칸을 TIMEOUT으로 비운 200을 반환합니다."
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
    except LLMConfigError as exc:
        _log_star_failure(request, False, exc, started)
        return _error(started, 503, "LLM_UNAVAILABLE", "STAR LLM 설정이 없어 분석할 수 없습니다.", False)
    except StarGenerationError as exc:
        _log_star_failure(request, exc.retryable, exc, started)
        return _error(started, 503, "LLM_UNAVAILABLE", "STAR LLM 호출에 실패했습니다.", exc.retryable)

    return Envelope(
        success=True,
        data=result,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=_elapsed_ms(started)),
        error=None,
    )


#: 다시 보내도 같은 GitHub 응답. 토큰 만료·권한 없음·잘못된 요청은 재시도하지 않는다.
#: 다만 2차 한도로 받은 403은 기다리면 풀려서 다시 시도할 수 있다.
_GITHUB_PERMANENT_STATUSES = frozenset({400, 401, 403, 422})


async def _run_until_deadline_or_disconnect(
    work: Awaitable[Any], http_request: Request
) -> Any:
    """작업을 마감 안에서 실행하고, 마감이 지나거나 호출자가 끊으면 작업을 취소한다.

    취소되면 아직 보내지 않은 LLM 호출은 나가지 않는다. 이미 스레드에서 진행 중인
    호출 하나는 클라이언트 timeout까지 끝나기를 기다리지 않고 결과만 버린다.
    """
    task = asyncio.ensure_future(work)
    stop_watching = asyncio.Event()
    watcher = asyncio.ensure_future(_wait_for_disconnect(http_request, stop_watching))
    try:
        done, _ = await asyncio.wait(
            {task, watcher},
            timeout=GROUPING_DEADLINE_SECONDS,
            return_when=asyncio.FIRST_COMPLETED,
        )
        if task in done:
            return task.result()
        if watcher in done:
            raise _ClientDisconnected
        raise asyncio.TimeoutError
    finally:
        # Starlette의 is_disconnected는 내부 cancel scope가 바깥 취소를 삼킬 수 있어
        # 감시 작업은 취소 대신 이벤트로 끝낸다.
        stop_watching.set()
        if not task.done():
            task.cancel()
        await asyncio.gather(task, watcher, return_exceptions=True)


async def _wait_for_disconnect(http_request: Request, stop: asyncio.Event) -> bool:
    """호출자가 끊으면 True, 감시를 멈추라는 신호를 받으면 False를 반환한다."""
    while not stop.is_set():
        if await http_request.is_disconnected():
            return True
        try:
            await asyncio.wait_for(stop.wait(), timeout=DISCONNECT_POLL_SECONDS)
        except asyncio.TimeoutError:
            pass
    return False


def _log_diff_evidence_failure(
    request: DiffEvidenceRequest, code: str, retryable: bool, exc: Exception, started: float
) -> None:
    log_failure(
        logger, "diff 근거 생성", path="/internal/analysis/diff-evidence", code=code, retryable=retryable,
        exc=exc, started=started, user_repository_id=request.user_repository_id,
        commits=len(request.commit_shas),
    )


def _log_star_failure(request: StarAnalysisRequest, retryable: bool, exc: Exception, started: float) -> None:
    log_failure(
        logger, "STAR 초안 생성", path="/internal/analysis/star", code="LLM_UNAVAILABLE", retryable=retryable,
        exc=exc, started=started, candidate_id=request.candidate_id, evidence=len(request.evidence),
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
