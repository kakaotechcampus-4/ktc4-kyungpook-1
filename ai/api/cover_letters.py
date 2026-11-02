"""자소서 카드 고르기(A1)·질문 만들기(A2)·초안 쓰기와 전체 검토(A3~A5) API.

세 API 는 LLM 이 없거나 실패해도 오류를 내지 않는다. 카드 고르기는 그 문항을 FAILED 로 돌려 사용자가 고르게 하고,
질문 만들기는 꼭 물을 항목만 틀 질문으로 묻고, 초안 쓰기는 카드 문장과 빈칸으로 만든 뼈대를 돌려준다.
"""

from __future__ import annotations

import time

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from api.openapi import INVALID_PAYLOAD_RESPONSE
from api.progress_stream import NDJSON, stream_with_progress
from schemas.common import Envelope
from schemas.cover_letter import (
    AssignmentRequest,
    AssignmentResult,
    DraftsRequest,
    DraftsResult,
    QuestionsRequest,
    QuestionsResult,
)
from services.cover_letter_asker import CoverLetterAsker
from services.cover_letter_assigner import CoverLetterAssigner
from services.cover_letter_drafter import CoverLetterDrafter
from services.llm_settings import EnvironmentLLMClient
from services.usage import usage_meta

router = APIRouter(prefix="/internal", tags=["cover-letters"], responses={400: INVALID_PAYLOAD_RESPONSE})

_STREAM_LINES = (
    "본문은 한 줄에 JSON 하나(NDJSON)이고, 진행 줄은 "
    '`{"type":"progress","step":"COVER_LETTER","done":0,"total":null,"item":{...}}` 입니다. '
    '`item` 은 단계 `{"kind":"STEP","step":{...}}` 이고, step 은 응답 steps 항목과 같은 모양으로 생기는 대로 보냅니다(단계 글은 200자에서 자름). '
)
_STREAM_RESULT = (
    '마지막 줄은 `{"type":"result","status":200,"body":{...}}` 이고, `status`·`body` 는 같은 요청을 스트리밍 없는 API 로 '
    "보냈을 때의 HTTP 상태와 응답 본문과 같습니다. 응답 헤더를 보낸 뒤에는 상태를 바꿀 수 없어, 오류도 HTTP 200 의 마지막 줄로 알립니다."
)

# 단계마다 모델을 따로 바꿔 잴 수 있게 작업 이름을 나눈다(GITORY_COVER_LETTER_WRITE_MODEL 등).
# 감독·다음 행동·전체 검토는 고르기만 하는 판단이라 한 이름(REVIEW)으로 묶어 함께 바꾼다
_judge = EnvironmentLLMClient("COVER_LETTER_REVIEW")
_assigner = CoverLetterAssigner(EnvironmentLLMClient("COVER_LETTER_PICK"), _judge)
_asker = CoverLetterAsker(EnvironmentLLMClient("COVER_LETTER_ASK"))
_drafter = CoverLetterDrafter(EnvironmentLLMClient("COVER_LETTER_WRITE"), _judge)


@router.post(
    "/cover-letter-assignments", response_model=Envelope[AssignmentResult],
    summary="자소서 문항마다 쓸 카드 고르기",
    description=(
        "문항마다 확정 카드 전부의 요약을 보고 1·2순위 카드를 고른 뒤, 문항이 둘 이상 카드를 고르면 감독이 겹침과 균형을 보고 "
        "최종 배정을 정합니다. 감독 답을 못 받으면 코드가 2순위로 겹침을 정리합니다(decided_by=CODE). "
        "카드 고르기 답을 못 받은 문항은 decided_by=FAILED 로 카드 없이 돌려주며, 이 API 는 LLM 실패로 오류를 내지 않습니다. "
        "steps 는 화면이 과정을 보일 순서입니다. 저장은 Spring 이 합니다."
    ),
)
async def assign_cards(request: AssignmentRequest) -> Envelope[AssignmentResult]:
    started = time.perf_counter()
    result, ended_by = await _assigner.assign(request)
    return Envelope(success=True, data=result, meta=usage_meta(started, ended_by), error=None)


@router.post(
    "/cover-letter-questions", response_model=Envelope[QuestionsResult],
    summary="자소서 문항마다 모자란 재료를 묻는 질문 만들기",
    description=(
        "꼭 있어야 하는 항목(경험 문항은 상황·행동·결과, 지원 동기는 지원 이유) 중 중심 카드 문장·이미 받은 답·메모에 없는 것을 "
        "required_missing 으로 돌려주고, 질문은 최대 3개로 그 항목을 모두 묻습니다. "
        "LLM 답을 못 받으면 required_missing 만 틀 질문으로 묻고(source=TEMPLATE), 물을 항목이 없으면 LLM 을 부르지 않습니다(source=NONE)."
    ),
)
async def create_questions(request: QuestionsRequest) -> Envelope[QuestionsResult]:
    started = time.perf_counter()
    result, ended_by = await _asker.ask(request)
    return Envelope(success=True, data=result, meta=usage_meta(started, ended_by), error=None)


@router.post(
    "/cover-letter-drafts", response_model=Envelope[DraftsResult],
    summary="자소서 문항마다 초안 쓰기와 전체 검토",
    description=(
        "문항마다 동시에 초안을 쓰고, 검사기(근거에 없는 수치·영문 낱말, 글자 수, 빈칸)에 걸리면 고쳐 쓰거나(FIX) "
        "검사기가 잘못 건 것으로 보고 표시만 합니다(KEEP). 다시 쓰기는 문항마다 2번까지이고, 그 뒤 남은 수치·낱말은 marks 로 표시합니다. "
        "모든 문항이 끝나면 감독이 함께 읽어 되풀이·근거 없는 역할·문항 벗어남을 문장 번호로 걸고, 걸린 문항만 한 번 더 씁니다. "
        "글자 수가 넘친 채로 남으면 자르지 않고 over_by 로 알립니다. 쓰기 답을 못 받은 문항은 카드 문장과 빈칸으로 만든 뼈대(source=SKELETON)이며, "
        "이 API 는 LLM 실패로 오류를 내지 않습니다."
    ),
)
async def create_drafts(request: DraftsRequest) -> Envelope[DraftsResult]:
    started = time.perf_counter()
    result, ended_by = await _drafter.draft(request)
    return Envelope(success=True, data=result, meta=usage_meta(started, ended_by), error=None)


@router.post(
    "/cover-letter-assignments/stream",
    response_class=StreamingResponse,
    summary="자소서 문항마다 쓸 카드 고르기 (진행 스트리밍)",
    description=(
        "`/internal/cover-letter-assignments` 와 같은 배정을 만들면서, 문항마다 카드를 고르는 즉시와 감독이 정할 때마다 단계를 보냅니다. "
        + _STREAM_LINES + _STREAM_RESULT
    ),
    responses={200: {"description": "진행 줄들과 마지막 결과 줄", "content": {NDJSON: {}}}},
)
async def assign_cards_stream(request: AssignmentRequest) -> StreamingResponse:
    return stream_with_progress("/internal/cover-letter-assignments/stream", lambda: assign_cards(request))


@router.post(
    "/cover-letter-drafts/stream",
    response_class=StreamingResponse,
    summary="자소서 문항마다 초안 쓰기와 전체 검토 (진행 스트리밍)",
    description=(
        "`/internal/cover-letter-drafts` 와 같은 초안을 만들면서, 쓰기·검사·다음 행동·전체 검토의 단계와 쓰는 도중의 글을 보냅니다. "
        + _STREAM_LINES
        + '쓰는 도중의 글은 `{"kind":"DRAFT_TEXT","question_id":1,"round":0,"text":"...","done":false}` 이고, '
        "문장이 끝날 때마다(문항마다 0.5초에 한 번까지) 그때까지 쓴 글 전체를 보내며, 다 쓰면 done=true 와 그 바퀴의 글을 보냅니다. "
        "round 0 은 첫 초안, 1 부터는 고쳐 쓴 바퀴이고 글은 비밀값을 가리고 5,000자에서 자릅니다. "
        + _STREAM_RESULT
    ),
    responses={200: {"description": "진행 줄들과 마지막 결과 줄", "content": {NDJSON: {}}}},
)
async def create_drafts_stream(request: DraftsRequest) -> StreamingResponse:
    return stream_with_progress("/internal/cover-letter-drafts/stream", lambda: create_drafts(request))
