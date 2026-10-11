"""기업·직무 조건 인덱싱과 확정 STAR 검색 내부 API."""

from __future__ import annotations

import os
import time

from fastapi import APIRouter, Depends

from rag.embedding import create_text_embedder, embedding_model_name
from rag.vector_store import ChromaJobCriterionStore
from schemas.common import Envelope, Meta
from schemas.matching import (
    JobPostingIndexRequest,
    JobPostingIndexResult,
    MatchingRequest,
    MatchingResponse,
)
from services.talent_matcher import MatchGradePolicy, TalentMatcher

router = APIRouter(prefix="/internal/matching", tags=["matching"])


def get_talent_matcher() -> TalentMatcher:
    """운영 구현을 만들되 테스트에서는 가짜 포트로 교체하는 의존성 경계."""
    maximum_distance = float(os.getenv("GITORY_MATCH_MAX_DISTANCE", "0.4"))
    return TalentMatcher(
        embedder=create_text_embedder(),
        store=ChromaJobCriterionStore(),
        maximum_distance=maximum_distance,
        grade_policy=MatchGradePolicy(
            grade_a_min_evidence=int(os.getenv("GITORY_MATCH_GRADE_A_MIN", "4")),
            grade_b_min_evidence=int(os.getenv("GITORY_MATCH_GRADE_B_MIN", "3")),
            grade_c_min_evidence=int(os.getenv("GITORY_MATCH_GRADE_C_MIN", "1")),
        ),
    )


@router.post("/postings/index", response_model=Envelope[JobPostingIndexResult])
async def index_job_postings(
    request: JobPostingIndexRequest,
    matcher: TalentMatcher = Depends(get_talent_matcher),
) -> Envelope[JobPostingIndexResult]:
    """Spring에 저장된 공고를 AI의 재생성 가능한 Chroma 인덱스에 반영한다."""
    started = time.perf_counter()
    result = matcher.index(request)
    return Envelope(
        success=True,
        data=result,
        meta=Meta(
            model=embedding_model_name(),
            tool_calls_made=1,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=None,
    )


@router.post("/search", response_model=Envelope[MatchingResponse])
async def search_job_matches(
    request: MatchingRequest,
    matcher: TalentMatcher = Depends(get_talent_matcher),
) -> Envelope[MatchingResponse]:
    """확정 STAR를 저장하지 않고 활성 공고 조건과 연결해 반환한다."""
    started = time.perf_counter()
    result = matcher.match(request)
    return Envelope(
        success=True,
        data=result,
        meta=Meta(
            model=embedding_model_name(),
            tool_calls_made=1,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=None,
    )
