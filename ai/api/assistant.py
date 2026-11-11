"""홈 한 줄 명령 도우미 내부 API."""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.failure_log import log_failure
from api.openapi import INVALID_PAYLOAD_RESPONSE, LLM_UNAVAILABLE_RESPONSE
from schemas.assistant import AssistantStep, AssistantStepRequest
from schemas.common import Envelope, ErrorDetail
from services.assistant_step import AssistantStepper, AssistantUnavailable, environment_client
from services.usage import usage_meta

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/assistant", tags=["assistant"], responses={400: INVALID_PAYLOAD_RESPONSE})

_stepper = AssistantStepper(environment_client())

_PATH = "/internal/assistant/next-step"


@router.post(
    "/next-step",
    response_model=Envelope[AssistantStep],
    responses={503: LLM_UNAVAILABLE_RESPONSE},
    summary="홈 한 줄 명령의 다음 행동 고르기",
    description=(
        "사용자가 이번 실행에서 적은 말(최대 10개)과 지금까지 실행한 도구 결과(최대 12개), 사용자의 상태(확정 카드 수·레포·관심 분야), "
        "더 부를 수 있는 횟수를 받아 도구 호출(CALL_TOOL)·사용자에게 묻기(ASK_USER)·끝내기(FINISH) 중 하나를 고릅니다. "
        "도구는 FIND_POSTINGS·MATCH_POSTING·START_COVER_LETTER·START_REPO_ANALYSIS·FILL_POSTING_GAPS·ASK_CARD_GAP·POLISH_COVER_LETTER 이고, "
        "도구 실행과 반복은 Spring 이 하며 이 API 는 상태를 들지 않습니다. "
        "고른 공고·레포·카드·자소서 ref 는 앞 단계 결과와 context(빈 칸이 있는 카드, 초안이 끝난 자소서)에 있던 것이고, "
        "모자란 조건 채우기는 이번 실행에서 맞춰 보고 모자란 조건이 남은 공고에만 부르며, 자소서 문항은 사용자가 적은 글에 있던 문장만 씁니다. "
        "규칙을 어긴 답은 한 번 더 묻고, 그래도 어기면 정해 둔 문장으로 바꿉니다. "
        "남은 횟수가 0 이면 도구를 고르지 않고, 지시문 같은 말은 LLM 을 부르지 않고 끝냅니다. "
        "LLM 답을 받지 못하면 503 LLM_UNAVAILABLE 입니다."
    ),
)
async def next_step(request: AssistantStepRequest):
    started = time.perf_counter()
    try:
        step, ended_by = await _stepper.next(request)
    except AssistantUnavailable as exc:
        log_failure(logger, "홈 도우미 다음 행동", path=_PATH, code="LLM_UNAVAILABLE",
                    retryable=exc.retryable, exc=exc, started=started)
        envelope = Envelope(
            success=False,
            data=None,
            meta=usage_meta(started, "FAILED"),
            error=ErrorDetail(code="LLM_UNAVAILABLE", message="홈 도우미 LLM 답을 받지 못했습니다.",
                              retryable=exc.retryable),
        )
        return JSONResponse(status_code=503, content=jsonable_encoder(envelope))
    return Envelope(success=True, data=step, meta=usage_meta(started, ended_by), error=None)
