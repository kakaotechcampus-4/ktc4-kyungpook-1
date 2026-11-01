"""매칭 2단계 조건 판단 내부 API."""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.failure_log import log_failure
from api.openapi import INVALID_PAYLOAD_RESPONSE, LLM_UNAVAILABLE_RESPONSE
from schemas.common import Envelope, ErrorDetail
from schemas.match_judgement import MatchJudgementRequest, MatchJudgementResult
from services.llm_settings import EnvironmentLLMClient
from services.match_judge import MatchJudge, MatchJudgeUnavailable
from services.usage import usage_meta

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/matching", tags=["matching"])

_judge = MatchJudge(EnvironmentLLMClient("MATCH_JUDGE"))


@router.post(
    "/judgements",
    response_model=Envelope[MatchJudgementResult],
    responses={400: INVALID_PAYLOAD_RESPONSE, 503: LLM_UNAVAILABLE_RESPONSE},
    summary="공고 조건마다 그 조건을 실제로 채우는 카드 고르기",
    description=(
        "공고 하나의 필수·우대 조건(최대 30개)과 카드(최대 40장)를 받아, 조건마다 채우는 카드와 한 줄 이유를 모두 돌려줍니다. "
        "met_by 에 없는 (카드, 조건) 짝은 못 채움입니다. 기술 이름만 같은 카드는 채움이 아니고, 연차·학위 같은 꼬리 조건은 보지 않습니다. "
        "마감 안에 규칙에 맞는 답을 받지 못하면 503 LLM_UNAVAILABLE 이고, 저장은 Spring 이 합니다."
    ),
)
async def judge_conditions(request: MatchJudgementRequest):
    started = time.perf_counter()
    try:
        result = await _judge.judge(request)
    except MatchJudgeUnavailable as exc:
        log_failure(logger, "조건 판단", path="/internal/matching/judgements", code="LLM_UNAVAILABLE",
                    retryable=exc.retryable, exc=exc, started=started)
        envelope = Envelope(
            success=False,
            data=None,
            meta=usage_meta(started, "FAILED"),
            error=ErrorDetail(code="LLM_UNAVAILABLE", message="조건 판단 LLM 답을 받지 못했습니다.",
                              retryable=exc.retryable),
        )
        return JSONResponse(status_code=503, content=jsonable_encoder(envelope))
    return Envelope(success=True, data=result, meta=usage_meta(started, "COMPLETED"), error=None)
