"""공식 채용공고 제공자에서 저장 후보 메타데이터를 수집하는 내부 API."""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from ports.job_posting_source import JobPostingSourcePort
from schemas.common import Envelope, ErrorDetail, Meta
from schemas.matching import (
    ExtractionOutcome,
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
    JobPostingDiscoveryRequest,
    JobPostingDiscoveryResult,
)
from services.job_posting_extractor import (
    JobPostingDetailExtractor,
    JobPostingEvidenceMismatch,
    LLMStructuredCallError,
    LunaJobPostingExtractionModel,
)
from services.saramin_job_source import JobPostingSourceError, SaraminJobPostingSource

router = APIRouter(prefix="/internal/job-postings", tags=["job-postings"])


def get_job_posting_source() -> JobPostingSourcePort:
    """현재 V1의 공식 제공자인 사람인 어댑터를 반환한다."""
    return SaraminJobPostingSource()


def get_job_posting_extractor() -> JobPostingDetailExtractor:
    """팀 운영 모델인 Luna를 사용하는 상세 본문 추출기."""
    return JobPostingDetailExtractor(LunaJobPostingExtractionModel())


@router.post("/discover", response_model=Envelope[JobPostingDiscoveryResult])
async def discover_job_postings(
    request: JobPostingDiscoveryRequest,
    source: JobPostingSourcePort = Depends(get_job_posting_source),
):
    """공고 메타데이터만 수집하며 직무 조건 본문을 지어내지 않는다."""
    started = time.perf_counter()
    try:
        execution = await source.discover(request)
    except JobPostingSourceError:
        envelope = Envelope(
            success=False,
            data=None,
            meta=Meta(
                model=None,
                tool_calls_made=1,
                processing_ms=int((time.perf_counter() - started) * 1000),
            ),
            error=ErrorDetail(
                code="INTERNAL_ERROR",
                message="채용공고 제공자 응답을 처리하지 못했습니다.",
                retryable=True,
            ),
        )
        return JSONResponse(status_code=502, content=jsonable_encoder(envelope))

    return Envelope(
        success=True,
        data=execution.result,
        meta=Meta(
            model=None,
            tool_calls_made=execution.api_calls,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=None,
    )


@router.post("/extract", response_model=Envelope[JobPostingDetailExtractionResult])
async def extract_job_posting_detail(
    request: JobPostingDetailExtractionRequest,
    extractor: JobPostingDetailExtractor = Depends(get_job_posting_extractor),
):
    """상세 본문에서 원문 인용으로 검증 가능한 직무 조건만 추출한다."""
    started = time.perf_counter()
    try:
        result = extractor.extract(request)
    except JobPostingEvidenceMismatch:
        return _extraction_error(
            status_code=422,
            code="EVIDENCE_MISMATCH",
            message="추출된 직무 조건의 근거를 공고 원문에서 확인할 수 없습니다.",
            retryable=False,
            started=started,
        )
    except LLMStructuredCallError:
        return _extraction_error(
            status_code=503,
            code="LLM_UNAVAILABLE",
            message="공고 상세 조건을 추출하지 못했습니다.",
            retryable=True,
            started=started,
        )

    return Envelope(
        success=True,
        data=result,
        meta=Meta(
            model="gpt-5.6-luna" if result.outcome == ExtractionOutcome.UPDATED else None,
            tool_calls_made=1 if result.outcome == ExtractionOutcome.UPDATED else 0,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=None,
    )


def _extraction_error(
    *,
    status_code: int,
    code: str,
    message: str,
    retryable: bool,
    started: float,
) -> JSONResponse:
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(
            model="gpt-5.6-luna",
            tool_calls_made=1,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
