"""저장소 README 요약 내부 API."""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.failure_log import log_failure
from api.openapi import INVALID_PAYLOAD_RESPONSE, LLM_UNAVAILABLE_RESPONSE
from schemas.common import Envelope, ErrorDetail
from schemas.repo_summary import RepoSummaryRequest, RepoSummaryResult
from services.repo_summarizer import RepoSummarizer, RepoSummaryUnavailable, environment_client
from services.usage import usage_meta

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["repos"])

_summarizer = RepoSummarizer(environment_client())


@router.post(
    "/repo-summary",
    response_model=Envelope[RepoSummaryResult],
    responses={400: INVALID_PAYLOAD_RESPONSE, 503: LLM_UNAVAILABLE_RESPONSE},
    summary="저장소 README 를 한 문장으로 요약하기",
    description=(
        "저장소 이름·언어·최상위 파일 이름(최대 30개)·README 앞부분을 받아 저장소 설명 한 문장을 돌려줍니다. "
        "README 는 앞 3,000자만 쓰고 더 길어도 거절하지 않습니다. "
        "무엇을 하는 프로젝트인지 알 수 없거나 답이 검사(재료에 없는 숫자, 길이)에 걸리면 실패가 아니라 200 이고 summary 가 null 입니다. "
        "LLM 답을 받지 못하면 503 LLM_UNAVAILABLE 이고, 저장은 Spring 이 합니다."
    ),
)
async def summarize_repo(request: RepoSummaryRequest):
    started = time.perf_counter()
    try:
        result = await _summarizer.summarize(request)
    except RepoSummaryUnavailable as exc:
        log_failure(logger, "저장소 요약", path="/internal/repo-summary", code="LLM_UNAVAILABLE",
                    retryable=exc.retryable, exc=exc, started=started, repo_id=request.repo_id)
        envelope = Envelope(
            success=False,
            data=None,
            meta=usage_meta(started, "FAILED"),
            error=ErrorDetail(code="LLM_UNAVAILABLE", message="저장소 요약 LLM 답을 받지 못했습니다.",
                              retryable=exc.retryable),
        )
        return JSONResponse(status_code=503, content=jsonable_encoder(envelope))
    return Envelope(success=True, data=result, meta=usage_meta(started, "COMPLETED"), error=None)
