"""공고 상세 본문에서 직무 조건을 뽑는 내부 API."""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.openapi import INVALID_PAYLOAD_RESPONSE
from schemas.common import Envelope, ErrorDetail, ErrorEnvelope, Meta
from schemas.matching import (
    ExtractionOutcome,
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
)
from services.job_posting_extractor import (
    JobPostingDetailExtractor,
    JobPostingEvidenceMismatch,
    LunaJobPostingExtractionModel,
)
from services.llm_executors import POSTING_EXTRACTION_EXECUTOR, run_llm_call
from services.llm_settings import LLMConfigError
from services.llm_structured_client import (
    LLMNonRetryableError,
    LLMStructuredCallError,
    LLMStructuredOutputTooLongError,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/job-postings", tags=["job-postings"])


def get_job_posting_extractor() -> JobPostingDetailExtractor:
    """팀 운영 모델인 Luna를 사용하는 상세 본문 추출기."""
    return JobPostingDetailExtractor(LunaJobPostingExtractionModel())


@router.post(
    "/extract",
    response_model=Envelope[JobPostingDetailExtractionResult],
    responses={
        400: INVALID_PAYLOAD_RESPONSE,
        422: {
            "model": ErrorEnvelope,
            "description": "EVIDENCE_MISMATCH: 필수 조건의 근거를 공고 원문에서 확인할 수 없거나 추출된 조건이 모두 빠짐. 재시도 불가.",
        },
        500: {
            "model": ErrorEnvelope,
            "description": "INTERNAL_ERROR: 예상하지 못한 내부 오류. 재시도 불가.",
        },
        503: {
            "model": ErrorEnvelope,
            "description": (
                "LLM_UNAVAILABLE: LLM 호출·출력 검증 실패(retryable=true), "
                "LLM 설정 누락·키·권한 오류·출력 길이 초과(retryable=false)."
            ),
        },
    },
)
async def extract_job_posting_detail(
    request: JobPostingDetailExtractionRequest,
    extractor: JobPostingDetailExtractor = Depends(get_job_posting_extractor),
):
    """상세 본문에서 원문 인용으로 검증 가능한 직무 조건만 추출한다."""
    started = time.perf_counter()
    try:
        result = await run_llm_call(POSTING_EXTRACTION_EXECUTOR, extractor.extract, request)
    except JobPostingEvidenceMismatch as exc:
        # 정상 판정이라 스택을 남기지 않는다(스택 글자를 찾는 로그 경보가 울리지 않게).
        logger.warning("공고 조건 추출 거절: posting=%s %s", request.external_posting_id, exc)
        return _error(
            started, 422, "EVIDENCE_MISMATCH",
            "필수 조건의 근거를 공고 원문에서 확인할 수 없거나 추출된 조건이 모두 빠졌습니다.", False, tool_calls_made=1,
        )
    except LLMConfigError:
        logger.warning("공고 조건 추출 실패: LLM 설정 없음", exc_info=True)
        return _error(
            started, 503, "LLM_UNAVAILABLE", "공고 조건 추출 LLM 설정이 없어 추출할 수 없습니다.", False
        )
    except (LLMNonRetryableError, LLMStructuredOutputTooLongError):
        logger.warning("공고 조건 추출 실패: 다시 해도 같은 LLM 오류", exc_info=True)
        return _error(
            started, 503, "LLM_UNAVAILABLE", "공고 상세 조건을 추출하지 못했습니다.", False, tool_calls_made=1
        )
    except LLMStructuredCallError:
        logger.warning("공고 조건 추출 실패: LLM 호출·출력 검증", exc_info=True)
        return _error(
            started, 503, "LLM_UNAVAILABLE", "공고 상세 조건을 추출하지 못했습니다.", True, tool_calls_made=1
        )
    except Exception:
        logger.exception("공고 조건 추출 실패: 예상하지 못한 오류")
        return _error(started, 500, "INTERNAL_ERROR", "공고 조건 추출 중 내부 오류가 발생했습니다.", False)

    return Envelope(
        success=True,
        data=result,
        meta=Meta(
            model="gpt-5.6-luna" if result.outcome == ExtractionOutcome.UPDATED else None,
            tool_calls_made=1 if result.outcome == ExtractionOutcome.UPDATED else 0,
            processing_ms=_elapsed_ms(started),
        ),
        error=None,
    )


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _error(
    started: float,
    status_code: int,
    code: str,
    message: str,
    retryable: bool,
    *,
    tool_calls_made: int = 0,
) -> JSONResponse:
    """실패를 공통 에러 봉투로 반환한다.

    내부 예외 문구는 응답에 싣지 않는다.
    """
    envelope = Envelope(
        success=False,
        data=None,
        meta=Meta(model=None, tool_calls_made=tool_calls_made, processing_ms=_elapsed_ms(started)),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
