"""자소서 카드 고르기(A1)·질문 만들기(A2)·초안 쓰기와 전체 검토(A3~A5)·문항 첨삭·채팅으로 재료 채우기 API.

앞의 세 API 는 LLM 이 없거나 실패해도 오류를 내지 않는다. 카드 고르기는 그 문항을 FAILED 로 돌려 사용자가 고르게 하고,
질문 만들기는 꼭 물을 항목만 틀 질문으로 묻고, 초안 쓰기는 카드 문장과 빈칸으로 만든 뼈대를 돌려준다.
첨삭·채팅 턴·답으로 다시 쓰기는 사용자가 누르고 기다리는 요청이라 실패를 숨기지 않고 503 LLM_UNAVAILABLE 로 알린다.
"""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, StreamingResponse

from api.failure_log import log_failure
from api.openapi import INVALID_PAYLOAD_RESPONSE, LLM_UNAVAILABLE_RESPONSE
from api.progress_stream import NDJSON, stream_with_progress
from schemas.common import Envelope, ErrorDetail
from schemas.cover_letter import (
    AssignmentRequest,
    AssignmentResult,
    DraftsRequest,
    DraftsResult,
    FillRequest,
    FillResult,
    PolishRequest,
    PolishResult,
    QuestionsRequest,
    QuestionsResult,
    TurnRequest,
    TurnResult,
)
from services.cover_letter_asker import CoverLetterAsker
from services.cover_letter_assigner import CoverLetterAssigner
from services.cover_letter_drafter import CoverLetterDrafter
from services.cover_letter_fill import CoverLetterFiller
from services.cover_letter_llm import CoverLetterLLMUnavailable
from services.cover_letter_polish import CoverLetterPolisher
from services.cover_letter_turns import CoverLetterTurns
from services.llm_settings import EnvironmentLLMClient
from services.usage import usage_meta

logger = logging.getLogger(__name__)

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
_polisher = CoverLetterPolisher(EnvironmentLLMClient("COVER_LETTER_POLISH"), _judge)
_turns = CoverLetterTurns(EnvironmentLLMClient("COVER_LETTER_TURN"))
# 답으로 다시 쓰기는 초안 쓰기의 고치기 경로라 초안과 같은 쓰기 모델을 쓴다
_filler = CoverLetterFiller(EnvironmentLLMClient("COVER_LETTER_WRITE"), _judge)

_POLISH_PATH = "/internal/cover-letter-polish"
_TURN_PATH = "/internal/cover-letter-turns"
_FILL_PATH = "/internal/cover-letter-fill"


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
    "/cover-letter-polish", response_model=Envelope[PolishResult],
    responses={503: LLM_UNAVAILABLE_RESPONSE},
    summary="자소서 문항 하나를 진단하고 고쳐 쓰기(첨삭)",
    description=(
        "문항 하나의 지금 글(target.current_text)을 읽고 종합 평가와 첨삭 포인트(아쉬운 점 WEAK 최대 4개 · 잘한 점 GOOD 최대 3개, "
        "분류 FIT·EVIDENCE·READABILITY·OVERLAP·TECH_TERMS)를 만든 뒤, 아쉬운 점과 검사기 문제를 초안 쓰기의 고치기 경로로 고쳐 씁니다. "
        "고친 글은 첫 초안과 같은 검사(근거 없는 수치·낱말, 기술 이름·코드 이름·저장소 말 상한)와 감독 검토(근거 없는 역할·문항 벗어남·"
        "밝히지 않은 추론, 걸리면 한 번 더 고쳐 씀)를 거쳐 문장 표시를 달고, points 의 sentence_seqs 는 고친 글(draft.sentences)의 문장 번호입니다. "
        "다른 문항 글(others)은 겹침(OVERLAP)을 보는 데만 쓰며, 겹친 문장이 고친 글에 남으면 REPEATED 로 표시합니다. "
        "고친 글에 그대로 남은 문장은 INFERRED 여부를 그대로 둡니다(inferred_seqs 에 있으면 남기고, 없으면 다시 달지 않음). "
        "진단이나 고쳐 쓰기 답을 60초 안에 받지 못하면 뼈대로 대신하지 않고 503 LLM_UNAVAILABLE(retryable=true)을 돌려줍니다."
    ),
)
async def polish_question(request: PolishRequest):
    started = time.perf_counter()
    try:
        result = await _polisher.polish(request)
    except CoverLetterLLMUnavailable as exc:
        log_failure(logger, "자소서 첨삭", path=_POLISH_PATH, code="LLM_UNAVAILABLE", retryable=True, exc=exc,
                    started=started, cover_letter_id=request.cover_letter_id)
        envelope = Envelope(
            success=False,
            data=None,
            meta=usage_meta(started, "FAILED"),
            error=ErrorDetail(code="LLM_UNAVAILABLE", message="자소서 첨삭 LLM 답을 받지 못했습니다.", retryable=True),
        )
        return JSONResponse(status_code=503, content=jsonable_encoder(envelope))
    return Envelope(success=True, data=result, meta=usage_meta(started, "COMPLETED"), error=None)


@router.post(
    "/cover-letter-turns", response_model=Envelope[TurnResult],
    responses={503: LLM_UNAVAILABLE_RESPONSE},
    summary="자소서 채팅의 다음 AI 말 만들기",
    description=(
        "초안이 나온 뒤 문항 하나(question)에 넣을 재료를 한 번에 질문 하나로 묻습니다. trigger 가 OPEN 이면 첫 질문을, "
        "USER_REPLY 면 thread 마지막 사용자 말을 받아 주는 한 줄과 함께 답(answer: 항목·'~다' 체로 정리한 사용자의 사실·충분한지)을 돌려주고, "
        "답이 얇으면 같은 항목 꼬리 질문(status ASKING, 같은 항목 2번까지 — 그 뒤엔 그대로 받음), 충분하면 status FILLED 입니다. "
        "SKIP 이면 LLM 없이 DONE 입니다. 질문은 세 줄 안(뭐가 비었는지·왜 필요한지·뭘 알려 달라는지와 예시)이고, 재료(카드 문장·받은 답)에 "
        "글자 그대로 있는 증거는 묻지 않습니다. 문항당 질문은 4번까지이고 넘으면 DONE 입니다. 지시문 같은 말이나 자소서 재료가 아닌 말은 "
        "정해진 글로 돌려보내고 묻던 질문을 다시 보입니다(OFF_TOPIC). 답 정리에 사용자가 말하지 않은 숫자·영문 이름이 들면 한 번 더 받고, "
        "그래도 들면 사용자의 말을 그대로 씁니다(ended_by=PARTIAL). thread 의 AI 턴에 status 를 보내면 질문 수를 그것으로 셉니다. "
        "LLM 답을 20초 안에 받지 못하면 503 LLM_UNAVAILABLE(retryable=true)입니다."
    ),
)
async def next_turn(request: TurnRequest):
    started = time.perf_counter()
    try:
        result, ended_by = await _turns.next(request)
    except CoverLetterLLMUnavailable as exc:
        return _unavailable("자소서 채팅 턴", _TURN_PATH, started, exc, request.cover_letter_id)
    return Envelope(success=True, data=result, meta=usage_meta(started, ended_by), error=None)


@router.post(
    "/cover-letter-fill", response_model=Envelope[FillResult],
    responses={503: LLM_UNAVAILABLE_RESPONSE},
    summary="채팅에서 받은 답으로 자소서 문항 하나 다시 쓰기",
    description=(
        "target.answers 에 든 filled_item 의 답으로 그 문항의 빈칸(blank) 자리만 다시 쓰고 나머지 문장은 그대로 둡니다. "
        "blank 가 null 이거나 글에 없으면 그 항목의 빈칸을 찾고, 그것도 없으면 그 내용이 들어갈 자리에 한 문장까지 더합니다. "
        "초안 쓰기의 고치기 경로로 쓰므로 근거 없는 수치·낱말 검사와 추론(INFERRED) 표시가 초안과 같고, 새로 쓴 문장만 감독이 한 번 더 봅니다. "
        "다시 쓴 글이 앞 글에 없던 문제로 검사에 걸리면 그 문제만 한 번 더 고쳐 쓰게 하고, 그래도 남거나 빈칸 자리 밖의 문장을 둘 이상 바꾸면 "
        "앞 글로 되돌리고 steps 에 REVERTED 를 남깁니다. "
        "그대로 남은 문장은 INFERRED 여부를 그대로 둡니다(target.inferred_seqs). "
        "쓰기 답을 60초 안에 받지 못하면 503 LLM_UNAVAILABLE(retryable=true)입니다."
    ),
)
async def fill_question(request: FillRequest):
    started = time.perf_counter()
    try:
        result = await _filler.fill(request)
    except CoverLetterLLMUnavailable as exc:
        return _unavailable("자소서 답으로 다시 쓰기", _FILL_PATH, started, exc, request.cover_letter_id)
    return Envelope(success=True, data=result, meta=usage_meta(started, "COMPLETED"), error=None)


def _unavailable(what: str, path: str, started: float, exc: Exception, cover_letter_id: int) -> JSONResponse:
    log_failure(logger, what, path=path, code="LLM_UNAVAILABLE", retryable=True, exc=exc, started=started,
                cover_letter_id=cover_letter_id)
    envelope = Envelope(
        success=False,
        data=None,
        meta=usage_meta(started, "FAILED"),
        error=ErrorDetail(code="LLM_UNAVAILABLE", message=f"{what} LLM 답을 받지 못했습니다.", retryable=True),
    )
    return JSONResponse(status_code=503, content=jsonable_encoder(envelope))


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
