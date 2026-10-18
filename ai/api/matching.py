"""기업·직무 조건 인덱싱과 확정 STAR 검색 내부 API."""

from __future__ import annotations

import logging
import os
import time
from typing import Callable

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.openapi import INVALID_PAYLOAD_RESPONSE
from rag.embedding import EmbeddingError, create_text_embedder, embedding_model_name
from rag.vector_store import ChromaJobCriterionStore
from schemas.common import Envelope, ErrorDetail, ErrorEnvelope, Meta
from schemas.matching import (
    JobPostingIndexRequest,
    JobPostingIndexResult,
    MatchingRequest,
    MatchingResponse,
)
from services.llm_executors import MATCHING_EXECUTOR, run_llm_call
from services.talent_matcher import TalentMatcher

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/matching", tags=["matching"])

MatcherFactory = Callable[[], TalentMatcher]

_MATCHING_ERROR_RESPONSES = {
    400: INVALID_PAYLOAD_RESPONSE,
    500: {
        "model": ErrorEnvelope,
        "description": "INTERNAL_ERROR: 예상하지 못한 내부 오류. 재시도 불가.",
    },
    503: {
        "model": ErrorEnvelope,
        "description": (
            "LLM_UNAVAILABLE: OpenAI 호환 임베딩 호출 실패(retryable=true), "
            "임베딩 설정 누락·키·권한 오류·하루 한도(retryable=false). "
            "기본 로컬 임베딩(chroma-default)의 실패는 503이 아니라 500 INTERNAL_ERROR."
        ),
    },
}


def build_talent_matcher() -> TalentMatcher:
    """환경 설정으로 운영 구현을 만든다."""
    return TalentMatcher(
        embedder=create_text_embedder(),
        store=ChromaJobCriterionStore(),
        maximum_distance=float(os.getenv("GITORY_MATCH_MAX_DISTANCE", "0.4")),
    )


def get_talent_matcher_factory() -> MatcherFactory:
    """설정 오류가 의존성 주입 단계의 봉투 없는 500이 되지 않도록 만들기는 호출 안에서 한다."""
    return build_talent_matcher


@router.post(
    "/postings/index",
    response_model=Envelope[JobPostingIndexResult],
    responses=_MATCHING_ERROR_RESPONSES,
)
async def index_job_postings(
    request: JobPostingIndexRequest,
    matcher_factory: MatcherFactory = Depends(get_talent_matcher_factory),
):
    """Spring에 저장된 공고를 AI의 재생성 가능한 Chroma 인덱스에 반영한다."""
    started = time.perf_counter()
    try:
        result = await run_llm_call(MATCHING_EXECUTOR, _index, matcher_factory, request)
    except Exception as exc:
        return _failure(started, "공고 색인", exc)
    return _success(started, result)


@router.post(
    "/search",
    response_model=Envelope[MatchingResponse],
    responses=_MATCHING_ERROR_RESPONSES,
)
async def search_job_matches(
    request: MatchingRequest,
    matcher_factory: MatcherFactory = Depends(get_talent_matcher_factory),
):
    """확정 STAR를 저장하지 않고 활성 공고 조건과 연결해 반환한다."""
    started = time.perf_counter()
    try:
        result = await run_llm_call(MATCHING_EXECUTOR, _search, matcher_factory, request)
    except Exception as exc:
        return _failure(started, "공고 검색", exc)
    return _success(started, result)


def _index(matcher_factory: MatcherFactory, request: JobPostingIndexRequest) -> JobPostingIndexResult:
    return matcher_factory().index(request)


def _search(matcher_factory: MatcherFactory, request: MatchingRequest) -> MatchingResponse:
    return matcher_factory().match(request)


def _success(started: float, result) -> Envelope:
    return Envelope(
        success=True,
        data=result,
        meta=Meta(
            model=embedding_model_name(),
            tool_calls_made=1,
            processing_ms=_elapsed_ms(started),
        ),
        error=None,
    )


def _failure(started: float, task: str, exc: Exception) -> JSONResponse:
    if isinstance(exc, EmbeddingError):
        logger.warning("%s 실패: 임베딩", task, exc_info=True)
        return _error(
            started, 503, "LLM_UNAVAILABLE", f"{task}에 필요한 임베딩을 만들지 못했습니다.", exc.retryable
        )
    logger.exception("%s 실패: 예상하지 못한 오류", task)
    return _error(started, 500, "INTERNAL_ERROR", f"{task} 중 내부 오류가 발생했습니다.", False)


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _error(started: float, status_code: int, code: str, message: str, retryable: bool) -> JSONResponse:
    """실패를 공통 에러 봉투로 반환한다.

    내부 예외 문구는 응답에 싣지 않는다.
    """
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=_elapsed_ms(started)),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
