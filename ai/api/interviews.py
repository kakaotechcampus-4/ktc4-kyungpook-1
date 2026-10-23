"""되묻기(interview-turns) API 엔드포인트 — B-1/B-2.

`POST /internal/interview-turns`: 고를 수 있는 칸 중 다음에 물을 칸과 질문·예상 답변을 LLM 으로 정한다.
LLM 이 없거나 실패하면 틀 질문으로 대신한다(services.interview_agent.InterviewAgent 참고).
`POST /internal/interview-measured-sentence`: '재 봤어요' 에 쓴 측정 결과로 R 문장을 다시 쓴다.
"""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from api.openapi import INVALID_PAYLOAD_RESPONSE
from schemas.common import Envelope, ErrorDetail, ErrorEnvelope, Meta
from schemas.interview import (
    InterviewAnswerRequest,
    InterviewAnswerResult,
    InterviewTurnRequest,
    InterviewTurnResult,
    MeasuredSentenceRequest,
    MeasuredSentenceResult,
)
from services.interview_answer_service import InterviewAnswerService
from services.interview_agent import InterviewAgent
from services.interview_llm import InterviewLLM, InterviewLLMUnavailable
from services.llm_settings import EnvironmentLLMClient, configured_model

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal", tags=["interview-turns"],
    responses={400: INVALID_PAYLOAD_RESPONSE},
)

# 화면에서 기다리는 요청이라 추론 강도를 low 로 시작한다(GITORY_INTERVIEW_REASONING_EFFORT 로 바꾼다)
_llm = InterviewLLM(EnvironmentLLMClient("INTERVIEW", default_reasoning_effort="low"))
_agent = InterviewAgent(llm=_llm)
_answer_service = InterviewAnswerService(question_agent=_agent)


@router.post(
    "/interview-turns", response_model=Envelope[InterviewTurnResult],
    summary="B-1 STAR 보강 질문 생성",
    description=(
        "고를 수 있는 칸(allowed_slots, 없으면 missing_slots 전부) 안에서 LLM 이 다음 칸·질문·예상 답변을 정하고, "
        "칸을 지정하지 않았으면 더 물을 것이 없을 때 COMPLETE 를 반환합니다. 첫 질문은 R 에 수치가 없으면 R 입니다. "
        "LLM 이 없거나 12초 안에 쓸 만한 답을 못 주면 틀 질문으로 대신하므로 이 API 는 LLM 실패로 오류를 내지 않습니다. "
        "DIRECT_CARD는 candidate=null이어야 합니다. 질문 순번·최종 횟수 제한·영속 저장은 Spring이 관리합니다."
    ),
)
async def create_interview_turn(request: InterviewTurnRequest) -> Envelope[InterviewTurnResult]:
    """되묻기 질문을 생성하거나(신규 턴), 더 물을 것이 없으면 완료를 반환한다.

    Args:
        request: 대상 카드, 카드 전체 커밋 문맥, 보강 대상 STAR 문장.

    Returns:
        공통 응답 봉투(Envelope)로 감싼 `InterviewTurnResult`. 질문 저장 순번과
        최종 횟수 제한은 Spring이 저장 시점에 관리한다.
    """
    started = time.perf_counter()
    result, used_llm = await _agent.next_turn(request)
    processing_ms = int((time.perf_counter() - started) * 1000)

    return Envelope(
        success=True,
        data=result,
        meta=Meta(
            model=configured_model("INTERVIEW") if used_llm else None,
            tool_calls_made=1 if used_llm else 0,
            processing_ms=processing_ms,
        ),
        error=None,
    )


@router.post(
    "/interview-measured-sentence",
    response_model=Envelope[MeasuredSentenceResult],
    summary="'재 봤어요' 측정 결과로 R 문장 다시 쓰기",
    description=(
        "사용자가 자유롭게 쓴 측정 결과와 지금 R 문장으로 결과 문장 하나를 씁니다. 저장하지 않습니다. "
        "문장 속 수치가 사용자 글에 있는지는 Spring 이 확인하고, 실패하면 Spring 이 틀 문장으로 대신합니다."
    ),
    responses={
        503: {
            "model": ErrorEnvelope,
            "description": "LLM_UNAVAILABLE: LLM 설정이 없거나 12초 안에 쓸 만한 문장을 받지 못함(retryable=true).",
        },
    },
)
async def write_measured_sentence(request: MeasuredSentenceRequest):
    """측정 결과를 넣은 R 문장을 돌려주거나, 받지 못하면 503 을 돌려준다."""
    started = time.perf_counter()
    try:
        sentence = await _llm.write_sentence(request)
    except InterviewLLMUnavailable:
        envelope = Envelope(
            success=False,
            data=None,
            meta=Meta(model=None, tool_calls_made=1, processing_ms=int((time.perf_counter() - started) * 1000)),
            error=ErrorDetail(code="LLM_UNAVAILABLE", message="수치 문장을 만들지 못했습니다.", retryable=True),
        )
        return JSONResponse(status_code=503, content=jsonable_encoder(envelope))
    return Envelope(
        success=True,
        data=MeasuredSentenceResult(card_id=request.card_id, sentence=sentence),
        meta=Meta(
            model=configured_model("INTERVIEW"),
            tool_calls_made=1,
            processing_ms=int((time.perf_counter() - started) * 1000),
        ),
        error=None,
    )


@router.post(
    "/interview-answer-evaluations",
    response_model=Envelope[InterviewAnswerResult],
    summary="B-2 사용자 답변 평가와 다음 행동 계산",
    description=(
        "현재 최소 정보량·회피성 표현 규칙으로 답변 충분성을 판단하며 LLM 판정기는 연결되지 않았습니다. "
        "ANSWERED이면 사용자 답변 원문을 resulting_statement로 반환합니다. "
        "SKIPPED/LATER는 Spring이 직접 처리하고 이 API를 호출하지 않습니다."
    ),
)
async def evaluate_interview_answer(
    request: InterviewAnswerRequest,
) -> Envelope[InterviewAnswerResult]:
    """사용자 답변을 평가해 STAR 문장과 다음 인터뷰 행동을 반환한다."""
    started = time.perf_counter()
    result = _answer_service.process_answer(request)
    processing_ms = int((time.perf_counter() - started) * 1000)

    return Envelope(
        success=True,
        data=result,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=processing_ms),
        error=None,
    )
