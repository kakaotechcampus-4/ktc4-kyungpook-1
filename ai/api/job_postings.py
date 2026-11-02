"""공고 상세 본문에서 직무 조건을 뽑고, 본문이 이미지인 공고의 글을 옮기는 내부 API."""

from __future__ import annotations

import asyncio
import logging
import time

from fastapi import APIRouter, Depends
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.failure_log import log_failure
from api.openapi import INVALID_PAYLOAD_RESPONSE
from schemas.common import Envelope, ErrorDetail, ErrorEnvelope
from schemas.matching import (
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
    JobPostingImageTextRequest,
    JobPostingImageTextResult,
)
from services.job_posting_extractor import (
    JobPostingDetailExtractor,
    JobPostingEvidenceMismatch,
    LunaJobPostingExtractionModel,
)
from services.llm_executors import POSTING_EXTRACTION_EXECUTOR, LLMCallSkipped, run_llm_call
from services.llm_settings import LLMConfigError
from services.llm_structured_client import (
    LLMNonRetryableError,
    LLMStructuredCallError,
    LLMStructuredOutputTooLongError,
)
from services.posting_image_reader import (
    MIN_SECONDS_PER_IMAGE,
    LunaImageTextModel,
    PostingImageDeadlineExceeded,
    PostingImageReader,
    PostingImageTextEmpty,
)
from services.usage import usage_meta

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/job-postings", tags=["job-postings"])

#: 이미지 6장을 한 장씩 읽어도(0단계 한 장 15.6초) 끝나게 두고, Spring 의 응답 대기 시간은 이보다 길게 둔다.
IMAGE_TEXT_DEADLINE_SECONDS = 180.0


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
            "필수 조건의 근거를 공고 원문에서 확인할 수 없거나 추출된 조건이 모두 빠졌습니다.", False,
        )
    except LLMConfigError as exc:
        _log_extraction_failure(request, "LLM_UNAVAILABLE", False, exc, started)
        return _error(
            started, 503, "LLM_UNAVAILABLE", "공고 조건 추출 LLM 설정이 없어 추출할 수 없습니다.", False
        )
    except (LLMNonRetryableError, LLMStructuredOutputTooLongError) as exc:
        _log_extraction_failure(request, "LLM_UNAVAILABLE", False, exc, started)
        return _error(
            started, 503, "LLM_UNAVAILABLE", "공고 상세 조건을 추출하지 못했습니다.", False
        )
    except LLMStructuredCallError as exc:
        _log_extraction_failure(request, "LLM_UNAVAILABLE", True, exc, started)
        return _error(
            started, 503, "LLM_UNAVAILABLE", "공고 상세 조건을 추출하지 못했습니다.", True
        )
    except Exception as exc:
        # code=INTERNAL_ERROR 줄이 운영 에러 경보에 걸리므로 traceback 없이도 알림은 그대로 간다
        _log_extraction_failure(request, "INTERNAL_ERROR", False, exc, started)
        return _error(started, 500, "INTERNAL_ERROR", "공고 조건 추출 중 내부 오류가 발생했습니다.", False)

    return Envelope(
        success=True,
        data=result,
        meta=usage_meta(started, "COMPLETED"),
        error=None,
    )


def get_posting_image_reader() -> PostingImageReader:
    """팀 운영 모델인 Luna 로 공고 본문 이미지를 읽는 리더."""
    return PostingImageReader(LunaImageTextModel())


@router.post(
    "/image-text",
    response_model=Envelope[JobPostingImageTextResult],
    responses={
        400: {
            **INVALID_PAYLOAD_RESPONSE,
            "description": (
                "INVALID_PAYLOAD: 요청 검증 실패. 이미지가 없거나 6장보다 많거나, 한 장이 5MB 보다 크거나, "
                "base64 가 아니면 LLM 을 부르지 않고 거절한다. 재시도 불가."
            ),
        },
        422: {
            "model": ErrorEnvelope,
            "description": (
                "EVIDENCE_MISMATCH: 모든 이미지에서 옮길 글이 나오지 않음(빈 결과). 같은 이미지면 재시도 불가. "
                "조건 뽑기와 같은 코드라 Spring 은 지금처럼 공고를 조건 없이 둔다."
            ),
        },
        500: {
            "model": ErrorEnvelope,
            "description": "INTERNAL_ERROR: 예상하지 못한 내부 오류. 재시도 불가.",
        },
        503: {
            "model": ErrorEnvelope,
            "description": (
                "LLM_UNAVAILABLE: LLM 호출·출력 검증 실패·요청 마감(180초) 초과(retryable=true), "
                "LLM 설정 누락·키·권한·요청 형식 오류·출력 길이 초과(retryable=false)."
            ),
        },
    },
)
async def read_job_posting_image_text(
    request: JobPostingImageTextRequest,
    reader: PostingImageReader = Depends(get_posting_image_reader),
):
    """공고 본문 이미지 속 글을 요약하지 않고 그대로 옮겨, 이미지 순서대로 이어 돌려준다."""
    started = time.perf_counter()
    deadline = asyncio.get_running_loop().time() + IMAGE_TEXT_DEADLINE_SECONDS
    try:
        result = await run_llm_call(
            POSTING_EXTRACTION_EXECUTOR, reader.read, request,
            deadline=deadline, min_seconds=MIN_SECONDS_PER_IMAGE,
        )
    except PostingImageTextEmpty as exc:
        # 새 오류 코드를 만들면 Spring 이 모르는 코드로 받아, 조건 뽑기의 '쓸 내용 없음' 과 같은 코드로 돌려준다.
        logger.warning("공고 이미지 글 옮기기 거절: posting=%s %s", request.external_posting_id, exc)
        return _error(started, 422, "EVIDENCE_MISMATCH", "공고 이미지에서 옮길 글을 찾지 못했습니다.", False)
    except (PostingImageDeadlineExceeded, LLMCallSkipped) as exc:
        _log_image_text_failure(request, "LLM_UNAVAILABLE", True, exc, started)
        return _error(started, 503, "LLM_UNAVAILABLE", "공고 이미지 글 옮기기 처리 시간이 초과되었습니다.", True)
    except LLMConfigError as exc:
        _log_image_text_failure(request, "LLM_UNAVAILABLE", False, exc, started)
        return _error(
            started, 503, "LLM_UNAVAILABLE", "공고 이미지 글 옮기기 LLM 설정이 없어 처리할 수 없습니다.", False
        )
    except (LLMNonRetryableError, LLMStructuredOutputTooLongError) as exc:
        _log_image_text_failure(request, "LLM_UNAVAILABLE", False, exc, started)
        return _error(started, 503, "LLM_UNAVAILABLE", "공고 이미지 글을 옮기지 못했습니다.", False)
    except LLMStructuredCallError as exc:
        _log_image_text_failure(request, "LLM_UNAVAILABLE", True, exc, started)
        return _error(started, 503, "LLM_UNAVAILABLE", "공고 이미지 글을 옮기지 못했습니다.", True)
    except Exception as exc:
        _log_image_text_failure(request, "INTERNAL_ERROR", False, exc, started)
        return _error(started, 500, "INTERNAL_ERROR", "공고 이미지 글 옮기기 중 내부 오류가 발생했습니다.", False)

    return Envelope(success=True, data=result, meta=usage_meta(started, "COMPLETED"), error=None)


def _log_image_text_failure(
    request: JobPostingImageTextRequest, code: str, retryable: bool, exc: Exception, started: float
) -> None:
    log_failure(
        logger, "공고 이미지 글 옮기기", path="/internal/job-postings/image-text", code=code, retryable=retryable,
        exc=exc, started=started, posting=request.external_posting_id,
    )


def _log_extraction_failure(
    request: JobPostingDetailExtractionRequest, code: str, retryable: bool, exc: Exception, started: float
) -> None:
    log_failure(
        logger, "공고 조건 추출", path="/internal/job-postings/extract", code=code, retryable=retryable,
        exc=exc, started=started, posting=request.external_posting_id,
    )


def _error(
    started: float,
    status_code: int,
    code: str,
    message: str,
    retryable: bool,
) -> JSONResponse:
    """실패를 공통 에러 봉투로 반환한다.

    내부 예외 문구는 응답에 싣지 않는다.
    """
    envelope = Envelope(
        success=False,
        data=None,
        meta=usage_meta(started, "FAILED"),
        error=ErrorDetail(code=code, message=message, retryable=retryable),
    )
    return JSONResponse(status_code=status_code, content=jsonable_encoder(envelope))
