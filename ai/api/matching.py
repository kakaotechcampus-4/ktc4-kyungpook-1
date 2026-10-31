"""기업·직무 조건 인덱싱과 확정 STAR 검색 내부 API."""

from __future__ import annotations

import logging
import os
import threading
import time
from typing import Callable, Optional

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.failure_log import log_failure
from api.openapi import INVALID_PAYLOAD_RESPONSE
from rag.embedding import EmbeddingError, create_text_embedder, embedding_model_name
from rag.vector_store import ChromaJobCriterionStore
from schemas.common import Envelope, ErrorDetail, ErrorEnvelope
from schemas.matching import (
    JobPostingIndexRequest,
    JobPostingIndexResult,
    JobPostingPruneRequest,
    JobPostingPruneResult,
    MatchingRequest,
    MatchingResponse,
)
from services.llm_executors import MATCHING_EXECUTOR, run_llm_call
from services.talent_matcher import TalentMatcher
from services.usage import usage_meta

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


_shared_matcher: Optional[TalentMatcher] = None
_shared_matcher_lock = threading.Lock()


def shared_talent_matcher() -> TalentMatcher:
    """처음 요청 때 한 번 만들어 재사용한다.

    임베딩 모델과 Chroma 연결을 요청마다 열면 느리다.
    설정 오류로 만들기가 실패하면 저장하지 않아 설정을 고친 뒤 다음 요청에서 다시 만든다.
    """
    global _shared_matcher
    with _shared_matcher_lock:
        if _shared_matcher is None:
            _shared_matcher = build_talent_matcher()
        return _shared_matcher


def get_talent_matcher_factory() -> MatcherFactory:
    """설정 오류가 의존성 주입 단계의 형식 없는 500이 되지 않도록 만들기는 호출 안에서 한다."""
    return shared_talent_matcher


@router.post(
    "/postings/index",
    response_model=Envelope[JobPostingIndexResult],
    responses=_MATCHING_ERROR_RESPONSES,
)
async def index_job_postings(
    request: JobPostingIndexRequest,
    matcher_factory: MatcherFactory = Depends(get_talent_matcher_factory),
):
    """Spring에 저장된 공고 조건을 AI의 재생성 가능한 Chroma 색인에 반영한다."""
    started = time.perf_counter()
    try:
        result = await run_llm_call(MATCHING_EXECUTOR, _index, matcher_factory, request)
    except Exception as exc:
        return _failure(started, "공고 색인", "/internal/matching/postings/index", exc)
    return _success(started, result)


@router.post(
    "/postings/prune",
    response_model=Envelope[JobPostingPruneResult],
    responses=_MATCHING_ERROR_RESPONSES,
)
async def prune_job_postings(
    request: JobPostingPruneRequest,
    matcher_factory: MatcherFactory = Depends(get_talent_matcher_factory),
):
    """남길 공고 id 목록에 없는 공고의 벡터를 색인에서 모두 지운다."""
    started = time.perf_counter()
    try:
        result = await run_llm_call(MATCHING_EXECUTOR, _prune, matcher_factory, request)
    except Exception as exc:
        return _failure(started, "공고 색인 정리", "/internal/matching/postings/prune", exc)
    return _success(started, result, embedded=False)


@router.post(
    "/search",
    response_model=Envelope[MatchingResponse],
    responses=_MATCHING_ERROR_RESPONSES,
)
async def search_job_matches(
    request: MatchingRequest,
    matcher_factory: MatcherFactory = Depends(get_talent_matcher_factory),
):
    """확정 STAR 문장을 저장하지 않고 거리 기준 안의 조건 id를 모두 반환한다."""
    started = time.perf_counter()
    try:
        result = await run_llm_call(MATCHING_EXECUTOR, _search, matcher_factory, request)
    except Exception as exc:
        return _failure(started, "공고 검색", "/internal/matching/search", exc)
    return _success(started, result)


def _index(matcher_factory: MatcherFactory, request: JobPostingIndexRequest) -> JobPostingIndexResult:
    return matcher_factory().index(request)


def _prune(matcher_factory: MatcherFactory, request: JobPostingPruneRequest) -> JobPostingPruneResult:
    return matcher_factory().prune(request)


def _search(matcher_factory: MatcherFactory, request: MatchingRequest) -> MatchingResponse:
    return matcher_factory().match(request)


def _success(started: float, result, embedded: bool = True) -> Envelope:
    return Envelope(
        success=True,
        data=result,
        meta=usage_meta(started, "COMPLETED", model=embedding_model_name() if embedded else None),
        error=None,
    )


def _failure(started: float, task: str, path: str, exc: Exception) -> JSONResponse:
    if isinstance(exc, EmbeddingError):
        log_failure(logger, task, path=path, code="LLM_UNAVAILABLE", retryable=exc.retryable, exc=exc, started=started)
        return _error(
            started, 503, "LLM_UNAVAILABLE", f"{task}에 필요한 임베딩을 만들지 못했습니다.", exc.retryable
        )
    # code=INTERNAL_ERROR 줄이 운영 에러 경보에 걸리므로 traceback 없이도 알림은 그대로 간다
    log_failure(logger, task, path=path, code="INTERNAL_ERROR", retryable=False, exc=exc, started=started)
    return _error(started, 500, "INTERNAL_ERROR", f"{task} 중 내부 오류가 발생했습니다.", False)


def _error(started: float, status_code: int, code: str, message: str, retryable: bool) -> JSONResponse:
    """실패를 공통 응답 형식의 오류로 반환한다.

    내부 예외 문구는 응답에 싣지 않는다.
    """
    envelope = Envelope(
        success=False,
        data=None,
        meta=usage_meta(started, "FAILED"),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
