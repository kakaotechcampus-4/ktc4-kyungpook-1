"""되묻기(interview-turns) API 엔드포인트 — B-1.

`POST /internal/interview-turns`: STAR 카드의 빈 칸(confidence=low)에 대해
결정적 템플릿 질문을 생성한다. 이번 PR에서는 LLM/DB/GitHub API를 호출하지
않는다(services.interview_agent.InterviewAgent 참고).
"""

from __future__ import annotations

import time

from fastapi import APIRouter

from schemas.common import Envelope, Meta
from schemas.interview import InterviewTurnRequest, InterviewTurnResult
from services.interview_agent import InterviewAgent

router = APIRouter(prefix="/internal", tags=["interview-turns"])

_agent = InterviewAgent()


@router.post("/interview-turns", response_model=Envelope[InterviewTurnResult])
async def create_interview_turn(request: InterviewTurnRequest) -> Envelope[InterviewTurnResult]:
    """되묻기 질문을 생성하거나(신규 턴), 더 물을 것이 없으면 완료를 반환한다.

    Args:
        request: 대상 카드, 카드 전체 커밋 문맥, 보강 대상 STAR 문장.

    Returns:
        공통 응답 봉투(Envelope)로 감싼 `InterviewTurnResult`. 질문 저장 순번과
        최종 횟수 제한은 Spring이 저장 시점에 관리한다.
    """
    started = time.perf_counter()
    result = _agent.build_turn(request)
    processing_ms = int((time.perf_counter() - started) * 1000)

    return Envelope(
        success=True,
        data=result,
        meta=Meta(model=None, tool_calls_made=0, processing_ms=processing_ms),
        error=None,
    )
