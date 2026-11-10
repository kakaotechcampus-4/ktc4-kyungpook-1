"""되묻기 답 처리(interview-replies): 판단은 LLM 이 하고, 문장·칸·수치 규칙은 코드가 지키는지 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any, Optional

import pytest
from fastapi.testclient import TestClient

import api.interviews as interviews_api
from main import app
from schemas.interview import InterviewReplyRequest
from services.interview_llm import (
    InterviewLLM,
    InterviewLLMUnavailable,
    QuestionOutput,
    ReplyExtraOutput,
    ReplyOutput,
    is_meaningless,
)
from services.llm_settings import LLMConfigError
from services.llm_structured_client import StructuredCallResult

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "interview" / "reply.json"


class FakeClient:
    """정해 둔 답(또는 예외)을 차례로 돌려주고, 부른 횟수와 입력을 기록한다."""

    def __init__(self, *answers: Any) -> None:
        self._answers = list(answers)
        self.payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.payloads.append(payload)
        answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _slots(**bodies: Optional[str]) -> list[dict]:
    return [{"star_slot": slot, "body": bodies.get(slot)} for slot in "STAR"]


def _request(**overrides: Any) -> InterviewReplyRequest:
    payload = {
        "card_id": 104,
        "card_title": "배포 시간 줄이기",
        "star_slot": "A",
        "question_text": "직접 하신 일이 무엇이었나요?",
        "answer_text": "바뀐 모듈만 다시 빌드하게 설정을 고쳤어요",
        "held_answers": [],
        "slots": _slots(),
        "follow_up_allowed": True,
    }
    payload.update(overrides)
    return InterviewReplyRequest.model_validate(payload)


def _apply(sentence: Optional[str] = "바뀐 모듈만 다시 빌드하도록 설정을 고쳤다.",
           extra: Optional[list[ReplyExtraOutput]] = None) -> ReplyOutput:
    return ReplyOutput(verdict="APPLY", sentence=sentence, extra=extra or [])


def _ask_more(follow_up: Optional[str] = "어떤 설정인지 알려 주세요.") -> ReplyOutput:
    return ReplyOutput(verdict="ASK_MORE", follow_up=follow_up, reason="어떤 설정인지가 빠져 있어요")


def _reply(client: FakeClient, request: Optional[InterviewReplyRequest] = None):
    return asyncio.run(InterviewLLM(client).reply(request or _request()))


@pytest.mark.parametrize("answer", ["하하하하하", "ㅋㅋㅋㅋ", "ㅎㅎ", "...", "?!?!", "ㅋㅎㅋㅎ"])
def test_meaningless_answer_is_off_topic_without_calling_llm(answer: str) -> None:
    """R1: 뜻 없는 글자뿐인 답은 LLM 을 부르지 않고 OFF_TOPIC 으로 답한다."""
    client = FakeClient()

    result = _reply(client, _request(answer_text=answer))

    assert (result.verdict, result.sentence, result.extra, client.payloads) == ("OFF_TOPIC", None, [], [])
    assert result.reason


@pytest.mark.parametrize("answer", ["1000", "네", "빌드가 느려서 캐시를 켰어요", "ab"])
def test_real_short_answers_are_not_caught_by_the_meaningless_check(answer: str) -> None:
    """R1 은 좁게 건다: 숫자가 든 답이나 짧은 실제 답은 LLM 으로 간다."""
    assert is_meaningless(answer) is False


@pytest.mark.parametrize("output", [_apply(sentence="  "), _apply(sentence="가" * 451)])
def test_apply_with_empty_or_too_long_sentence_is_retried_then_unusable(output: ReplyOutput) -> None:
    """R2: APPLY 문장이 비었거나 칸 상한을 넘으면 한 번 다시 요청하고, 그래도 같으면 쓸 수 없다."""
    client = FakeClient(output, output)

    with pytest.raises(InterviewLLMUnavailable):
        _reply(client)

    assert len(client.payloads) == 2


def test_apply_retry_accepts_the_second_try() -> None:
    """R2: 첫 결과가 틀리고 다시 받은 결과가 맞으면 그 결과를 쓴다."""
    client = FakeClient(_apply(sentence=""), _apply())

    result = _reply(client)

    assert (result.verdict, result.sentence) == ("APPLY", "바뀐 모듈만 다시 빌드하도록 설정을 고쳤다.")


def test_number_in_none_of_the_three_sources_is_retried_then_unusable() -> None:
    """R3: 답·앞서 둔 답·기존 글 어디에도 없는 수치가 문장에 있으면 다시 요청하고, 그래도 남으면 쓸 수 없다."""
    invented = _apply(sentence="설정을 고쳐 배포 시간을 30% 줄였다.")
    client = FakeClient(invented, invented)

    with pytest.raises(InterviewLLMUnavailable):
        _reply(client)


def test_number_from_held_answer_or_current_text_is_allowed() -> None:
    """R3: 수치가 앞서 둔 답이나 그 칸의 기존 글에 있으면 지어낸 것이 아니다."""
    held = _request(held_answers=["빌드를 12분에서 4분으로 줄였어요"], slots=_slots(A="캐시를 3개 붙였다"))
    sentence = "빌드를 12분에서 4분으로 줄이고 캐시를 3개 붙였다."
    client = FakeClient(_apply(sentence=sentence))

    result = _reply(client, held)

    assert result.sentence == sentence


@pytest.mark.parametrize("slots, extra, expected", [
    (_slots(), [("R", "배포가 빨라졌다.")], ["R"]),
    (_slots(), [("A", "설정을 또 고쳤다.")], []),
    (_slots(S="이미 쓴 상황이다."), [("S", "상황을 바꿨다.")], []),
    (_slots(), [("R", "첫 문장이다."), ("R", "둘째 문장이다.")], ["R"]),
    (_slots(), [("R", "배포 시간이 4분이 되었다.")], []),
    (_slots(), [("R", " ")], []),
    (_slots(), [("R", "가" * 451)], []),
    (_slots(), [("R", "결과는 ___ 였다.")], []),
])
def test_extra_keeps_only_valid_sentences_for_other_empty_slots(slots, extra, expected) -> None:
    """R4/R7: extra 는 질문한 칸·글이 있는 칸·중복·답에 없는 수치·빈 문장·상한 초과·빈칸을 버린다(다시 요청하지 않는다)."""
    outputs = [ReplyExtraOutput(star_slot=slot, sentence=sentence) for slot, sentence in extra]
    client = FakeClient(_apply(extra=outputs))

    result = _reply(client, _request(slots=slots))

    assert [item.star_slot for item in result.extra] == expected
    assert len(client.payloads) == 1


def test_ask_more_when_follow_up_is_not_allowed_is_retried_then_unusable() -> None:
    """R5: 더 물으면 안 되는데 ASK_MORE 가 오면 다시 요청하고, 그래도 같으면 쓸 수 없다."""
    client = FakeClient(_ask_more(), _ask_more())

    with pytest.raises(InterviewLLMUnavailable):
        _reply(client, _request(follow_up_allowed=False))

    assert len(client.payloads) == 2


@pytest.mark.parametrize("follow_up", [None, "  ", "가" * 121])
def test_ask_more_needs_a_follow_up_within_the_limit(follow_up) -> None:
    """R6: ASK_MORE 는 120자 이내의 질문이 있어야 하고, 아니면 다시 요청한 뒤 쓸 수 없다."""
    client = FakeClient(_ask_more(follow_up), _ask_more(follow_up))

    with pytest.raises(InterviewLLMUnavailable):
        _reply(client)


def test_only_apply_keeps_sentence_and_only_ask_more_keeps_follow_up() -> None:
    """R6: ASK_MORE·OFF_TOPIC·DONT_KNOW 는 문장과 extra 를 비우고, APPLY·DONT_KNOW 는 follow_up 이 없다."""
    stray = ReplyExtraOutput(star_slot="R", sentence="결과가 났다.")
    noisy = dict(sentence="새 문장이다.", extra=[stray], follow_up="더 알려 주세요.", reason="사유예요")

    ask_more = _reply(FakeClient(ReplyOutput(verdict="ASK_MORE", **noisy)))
    off_topic = _reply(FakeClient(ReplyOutput(verdict="OFF_TOPIC", **noisy)))
    dont_know = _reply(FakeClient(ReplyOutput(verdict="DONT_KNOW", **noisy)))
    applied = _reply(FakeClient(ReplyOutput(verdict="APPLY", **noisy)))

    for result in (ask_more, off_topic, dont_know):
        assert (result.sentence, result.extra) == (None, [])
    assert (ask_more.follow_up, off_topic.follow_up, dont_know.follow_up, applied.follow_up) == (
        "더 알려 주세요.", None, None, None)
    assert (dont_know.reason, applied.reason) == (None, None)


@pytest.mark.parametrize("sentence", ["___ 를 고쳤다.", "설정을 ___ 로 고쳤다."])
def test_sentence_with_blank_is_retried_then_unusable(sentence: str) -> None:
    """R7: 빈칸(___)이 든 문장은 받지 않는다."""
    client = FakeClient(_apply(sentence=sentence), _apply(sentence=sentence))

    with pytest.raises(InterviewLLMUnavailable):
        _reply(client)


def test_endpoint_accepts_the_contract_request_and_answers_in_the_contract_shape(monkeypatch) -> None:
    """R8: 계약 파일의 요청을 그대로 받고, 계약 파일의 응답과 같은 키 모양으로 답한다."""
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    request = contract["springToAi"]["expectedRequest"]
    expected = contract["aiToSpring"]["applied"]["data"]
    fake = FakeClient(_apply(sentence=expected["sentence"],
                             extra=[ReplyExtraOutput(star_slot="R", sentence=expected["extra"][0]["sentence"])]))
    monkeypatch.setattr(interviews_api, "_llm", InterviewLLM(fake))

    response = TestClient(app).post("/internal/interview-replies", json=request)

    assert response.status_code == 200
    assert response.json()["data"] == expected
    assert fake.payloads[0]["held_answers"] == request["held_answers"]


@pytest.mark.parametrize("answers", [(LLMConfigError("설정 없음"),), (_apply(sentence=""), _apply(sentence=""))])
def test_endpoint_returns_503_when_llm_is_missing_or_gives_no_usable_answer(monkeypatch, answers) -> None:
    """R9: LLM 이 없거나 쓸 만한 답을 못 받으면 503 LLM_UNAVAILABLE 이다."""
    request = json.loads(CONTRACT.read_text(encoding="utf-8"))["springToAi"]["expectedRequest"]
    monkeypatch.setattr(interviews_api, "_llm", InterviewLLM(FakeClient(*answers)))

    response = TestClient(app).post("/internal/interview-replies", json=request)

    assert response.status_code == 503
    assert response.json()["error"] == {"code": "LLM_UNAVAILABLE", "message": "답을 처리하지 못했습니다.",
                                        "retryable": True}


def test_suggested_answers_over_forty_characters_are_dropped() -> None:
    """질문의 예상 답변은 눌러 고르는 짧은 말이라 40자를 넘으면 버린다."""
    from services.interview_agent import InterviewAgent
    from schemas.interview import InterviewTurnRequest

    turn = InterviewTurnRequest.model_validate({
        "card_id": 7, "source_type": "DIRECT_CARD", "max_turns": 3,
        "missing_slots": [{"star_slot": "S", "statement_seq": 1, "body": None, "confidence": "LOW"}],
    })
    client = FakeClient(QuestionOutput(action="ASK", star_slot="S", question="어떤 상황이었나요?",
                                       suggested_answers=["가" * 41, "배포가 느렸어요"]))

    result, _ = asyncio.run(InterviewAgent(llm=InterviewLLM(client)).next_turn(turn))

    assert result.suggested_answers == ["배포가 느렸어요"]
