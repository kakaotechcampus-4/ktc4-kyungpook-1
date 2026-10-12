"""GitHub 활동을 경험과 STAR로 변환하는 내부 분석 API."""

from __future__ import annotations

import asyncio
import time
from typing import Any, Awaitable

from fastapi import APIRouter, Depends, Request
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
        return _llm_unavailable(started, "그룹화 처리 시간이 초과되었습니다.", True)
    except _ClientDisconnected:
        # 받을 사람이 없지만 남은 LLM 호출은 이미 취소했다. 응답은 형식만 맞춘다.
        return _llm_unavailable(started, "호출자가 연결을 끊었습니다.", True)
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
