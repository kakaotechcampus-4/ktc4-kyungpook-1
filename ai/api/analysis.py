"""GitHub 활동을 경험과 STAR로 변환하는 내부 분석 API."""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Awaitable

from fastapi import APIRouter, Depends, Header, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, StreamingResponse

from api.collection import get_repository_activity_port
from api.failure_log import log_failure
from api.progress_stream import NDJSON, STREAM_DESCRIPTION, stream_with_progress
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
from schemas.common import EndedBy, Envelope, ErrorDetail, ErrorEnvelope
from services.analysis_pipeline import AnalysisPipeline
from services.diff_analyzer import DiffAnalysisError
from services.github_collector import GithubApiError, GithubResourceNotFound, GithubUnauthorized
from services.grouping_llm import GroupingLlmConfigError, GroupingLlmError
from services.llm_settings import LLMConfigError
from services.star_generator import StarGenerationError
from services.usage import usage_meta

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
    deadline = asyncio.get_running_loop().time() + GROUPING_DEADLINE_SECONDS
    try:
        result = await _run_until_deadline_or_disconnect(
            pipeline.group_experiences(request, deadline=deadline), http_request
        )
    except asyncio.TimeoutError:
        logger.warning("경험 그룹화 실패: 요청 마감 %s초 초과", GROUPING_DEADLINE_SECONDS)
        return _llm_unavailable(started, "그룹화 처리 시간이 초과되었습니다.", True)
    except _ClientDisconnected:
        # 받을 사람이 없지만 남은 LLM 호출은 이미 취소했다. 응답은 형식만 맞춘다.
        return _llm_unavailable(started, "호출자가 연결을 끊었습니다.", True)
    except GroupingLlmConfigError as exc:
        _log_grouping_failure(request, False, exc, started)
        return _llm_unavailable(started, "그룹화 LLM 설정이 없어 분석할 수 없습니다.", False)
    except GroupingLlmError as exc:
        _log_grouping_failure(request, True, exc, started)
        return _llm_unavailable(started, "그룹화 LLM 호출에 실패했습니다.", True)
    return Envelope(
        success=True,
        data=result,
        meta=usage_meta(started, "PARTIAL" if result.verdict == "PARTIAL" else "COMPLETED"),
        error=None,
    )


class _StaysConnected:
    """스트리밍 응답은 호출자가 끊으면 스트림을 닫아 작업을 취소하므로, 요청을 두 곳에서 읽지 않게 끊김 감시를 끈다."""

    async def is_disconnected(self) -> bool:
        return False


@router.post(
    "/groups/stream",
    response_class=StreamingResponse,
    summary="A 경험 후보 그룹화 (진행 스트리밍)",
    description=(
        "`/internal/analysis/groups` 와 같은 그룹화를 하면서, 작업 단위 묶음 하나를 묶을 때마다(COMPRESS) "
        "후보 묶음 하나에 제목·이유를 붙일 때마다(REASON) 진행을 보냅니다. "
        + STREAM_DESCRIPTION
    ),
    responses={200: {"description": "진행 줄들과 마지막 결과 줄", "content": {NDJSON: {}}}},
)
async def group_experiences_stream(
    request: ExperienceGroupingRequest,
    pipeline: AnalysisPipeline = Depends(get_analysis_pipeline),
) -> StreamingResponse:
    """그룹화 API 와 같은 처리·마감·오류 응답을 쓰고, 그 사이 진행만 줄로 더 보낸다."""
    return stream_with_progress(
        "/internal/analysis/groups/stream",
        lambda: group_experiences(request, _StaysConnected(), pipeline),
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
    except GithubUnauthorized as exc:
        _log_diff_evidence_failure(request, "GITHUB_UNAUTHORIZED", False, exc, started)
        return _error(started, 401, "GITHUB_UNAUTHORIZED", "GitHub 이 토큰을 거절했습니다. GitHub 을 다시 연결해야 합니다.", False)
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
        meta=usage_meta(
            started, "PARTIAL" if execution.result.partial else "COMPLETED", github_calls=execution.api_calls
        ),
        error=None,
    )


@router.post(
    "/star", response_model=Envelope[StarAnalysisResponse],
    summary="A 확정 경험의 STAR 초안 생성",
    description=(
        "/internal/analysis/diff-evidence가 만든 커밋별 근거(1개 이상)로 인터뷰 전 STAR 초안을 생성합니다. "
        "근거 커밋과 확인 답변이 없는 문장은 비우고 removed_claims에 남깁니다. "
        "근거 커밋 없이 확인 답변만 있는 문장은 NEEDS_REVIEW(LOW)로 채웁니다. "
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
        meta=usage_meta(started, _star_ended_by(result)),
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


def _log_grouping_failure(
    request: ExperienceGroupingRequest, retryable: bool, exc: Exception, started: float
) -> None:
    log_failure(
        logger, "경험 그룹화", path="/internal/analysis/groups", code="LLM_UNAVAILABLE", retryable=retryable,
        exc=exc, started=started, commits=len(request.commits),
    )


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


def _star_ended_by(result: StarAnalysisResponse) -> EndedBy:
    """마감에 걸려 비운 칸이 하나라도 있으면 일부만 끝난 것이다."""
    timed_out = any(field.insufficient_reason_code == "TIMEOUT" for field in result.star.values())
    return "PARTIAL" if timed_out else "COMPLETED"


def _error(started: float, status_code: int, code: str, message: str, retryable: bool) -> JSONResponse:
    """실패를 공통 에러 봉투로 반환한다. 내부 예외 문구는 응답에 싣지 않는다."""
    envelope = Envelope(
        success=False,
        data=None,
        meta=usage_meta(started, "FAILED"),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
