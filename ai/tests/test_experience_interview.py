"""경험 카드 되묻기: 답 다듬기·첫 질문·빈칸 틀·문항 유형이 합의한 규칙대로인지 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
from typing import Any, Optional

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import api.interviews as interviews_api
from main import app
from schemas.interview import CardThemesRequest, InterviewTurnRequest, PolishedSentenceRequest
from services.interview_agent import InterviewAgent
from services.interview_llm import (
    POLISH_RETRY_RULE,
    InterviewLLM,
    QuestionOutput,
    SentenceOutput,
    ThemesOutput,
)
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult


class FakeClient:
    """정해 둔 답(또는 예외)을 차례로 돌려주고, 받은 프롬프트와 입력을 기록한다."""

    def __init__(self, *answers: Any) -> None:
        self._answers = list(answers)
        self.prompts: list[str] = []
        self.payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.prompts.append(system_prompt)
        self.payloads.append(payload)
        answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _polish(client: FakeClient, answer: str, slot: str = "R"):
    request = PolishedSentenceRequest(card_id=7, star_slot=slot, question_text="팀은 어떻게 달라졌나요?",
                                      answer_text=answer)
    return asyncio.run(InterviewLLM(client).polish(request))


def _slot(slot: str, body: Optional[str] = None) -> dict:
    return {"star_slot": slot, "statement_seq": 1, "body": body, "confidence": "HIGH"}


def _turn(**overrides: Any) -> InterviewTurnRequest:
    payload = {
        "card_id": 7,
        "source_type": "DIRECT_CARD",
        "card_kind": "QUALITATIVE",
        "max_turns": 8,
        "missing_slots": [_slot("S"), _slot("T"), _slot("A"), _slot("R")],
    }
    payload.update(overrides)
    return InterviewTurnRequest.model_validate(payload)


def _next(client: FakeClient, request: InterviewTurnRequest):
    return asyncio.run(InterviewAgent(llm=InterviewLLM(client)).next_turn(request))


ACTIVITY = {"name": "캠퍼스 해커톤", "type": "CONTEST", "role": "백엔드"}
ANSWERED_S = [{"star_slot": "S", "question_text": "상황은요?", "outcome": "ANSWERED", "answer_text": "회의가 길었다"}]


def test_polish_sends_the_answer_with_the_slot_limit() -> None:
    """답과 그 칸의 글자 상한을 보내고, 다듬은 문장을 그대로 돌려준다."""
    client = FakeClient(SentenceOutput(sentence=" 회의 방식을 바꾼 뒤 팀원 사이의 다툼이 줄었다. "))

    result = _polish(client, "회의 방식을 바꾸고 나서 다들 덜 다퉜어요", slot="S")

    assert client.payloads[0]["answer"] == "회의 방식을 바꾸고 나서 다들 덜 다퉜어요"
    assert client.payloads[0]["max_chars"] == 250
    assert (result.sentence, result.unsourced_numbers) == ("회의 방식을 바꾼 뒤 팀원 사이의 다툼이 줄었다.", [])


def test_invented_number_is_sent_back_once_and_fixed() -> None:
    """다듬은 문장에 답에 없는 숫자가 생기면 그 숫자를 빼라고 한 번 돌려보내고, 고친 문장을 쓴다."""
    client = FakeClient(
        SentenceOutput(sentence="회의 방식을 바꿔 팀 내 갈등을 50% 줄였다."),
        SentenceOutput(sentence="회의 방식을 바꾼 뒤 팀원 사이의 다툼이 줄었다."),
    )

    result = _polish(client, "회의 방식을 바꾸고 나서 다들 덜 다퉜어요")

    assert client.prompts[1].endswith(POLISH_RETRY_RULE)
    assert client.payloads[1]["previous_sentence"] == "회의 방식을 바꿔 팀 내 갈등을 50% 줄였다."
    assert client.payloads[1]["unsourced_numbers"] == ["50%"]
    assert (result.sentence, result.unsourced_numbers) == ("회의 방식을 바꾼 뒤 팀원 사이의 다툼이 줄었다.", [])


def test_number_left_after_the_retry_is_reported() -> None:
    """한 번 고친 뒤에도 남은 숫자는 화면이 강조하도록 함께 돌려준다."""
    client = FakeClient(
        SentenceOutput(sentence="갈등을 50% 줄였다."),
        SentenceOutput(sentence="갈등을 절반인 50% 줄였다."),
    )

    result = _polish(client, "다들 덜 다퉜어요")

    assert len(client.payloads) == 2
    assert (result.sentence, result.unsourced_numbers) == ("갈등을 절반인 50% 줄였다.", ["50%"])


def test_number_from_the_answer_is_not_sent_back() -> None:
    """사용자가 직접 말한 숫자(체감 포함)는 지어낸 숫자가 아니라 다시 부르지 않는다."""
    client = FakeClient(SentenceOutput(sentence="체감상 회의 시간을 50% 정도 줄였다."))

    result = _polish(client, "체감상 회의가 50% 정도 짧아졌어요")

    assert len(client.payloads) == 1
    assert result.unsourced_numbers == []


def test_failed_retry_keeps_the_first_sentence_with_its_numbers() -> None:
    """돌려보낸 호출이 실패하면 첫 문장과 걸린 숫자를 그대로 돌려준다."""
    client = FakeClient(SentenceOutput(sentence="갈등을 50% 줄였다."), LLMStructuredCallError("실패"))

    result = _polish(client, "다들 덜 다퉜어요")

    assert (result.sentence, result.unsourced_numbers) == ("갈등을 50% 줄였다.", ["50%"])


def test_blank_or_too_long_polish_is_requested_again() -> None:
    """빈 문장이나 저장 칸(1,000자)을 넘는 문장은 다시 요청한다."""
    client = FakeClient(SentenceOutput(sentence="가" * 1_001), SentenceOutput(sentence="다툼이 줄었다."))

    result = _polish(client, "다들 덜 다퉜어요")

    assert len(client.payloads) == 2
    assert result.sentence == "다툼이 줄었다."


def test_answer_secrets_are_masked_before_the_llm() -> None:
    """답에 든 이메일·토큰은 LLM 에 보내기 전에 가린다."""
    token = "ghp_" + "x" * 36
    client = FakeClient(SentenceOutput(sentence="문의를 받았다."))

    _polish(client, f"kim@example.com 으로 {token} 를 받았어요")

    assert "kim@example.com" not in client.payloads[0]["answer"] and token not in client.payloads[0]["answer"]


def test_polish_endpoint_returns_503_when_unavailable(monkeypatch) -> None:
    """다듬지 못하면 503 LLM_UNAVAILABLE 로 알려 Spring 이 사용자 답을 그대로 보여 준다."""
    monkeypatch.setattr(interviews_api, "_llm", InterviewLLM(FakeClient(LLMStructuredCallError("실패"))))

    response = TestClient(app).post("/internal/interview-polished-sentence",
                                    json={"card_id": 7, "star_slot": "R", "answer_text": "덜 다퉜어요"})

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "LLM_UNAVAILABLE"


def test_empty_experience_card_asks_s_first() -> None:
    """빈 경험 카드의 첫 질문은 S 로 정해 LLM 에 넘기고, 카드 종류도 함께 보낸다."""
    client = FakeClient(QuestionOutput(action="ASK", star_slot="S", question="어떤 상황이었나요?"))

    result, _ = _next(client, _turn())

    assert (client.payloads[0]["fixed_slot"], client.payloads[0]["card_kind"]) == ("S", "QUALITATIVE")
    assert result.target_star_slot == "S"


def test_experience_card_with_s_written_lets_the_llm_choose() -> None:
    """S 가 이미 있는 경험 카드는 기술 카드의 R 우선 규칙을 쓰지 않고 LLM 이 첫 칸을 고른다."""
    client = FakeClient(QuestionOutput(action="ASK", star_slot="T", question="맡은 일은요?"))

    _next(client, _turn(missing_slots=[_slot("S", "회의가 길었다"), _slot("T"), _slot("A"), _slot("R")]))

    assert client.payloads[0]["fixed_slot"] is None


def test_activity_card_keeps_only_blank_templates() -> None:
    """활동 카드는 활동을 보내고, 빈칸(___)이 없는 예상 답변은 지어낸 문장이라 버린다."""
    client = FakeClient(QuestionOutput(
        action="ASK", star_slot="S", question="해커톤에서 어떤 상황이었나요?",
        suggested_answers=["캠퍼스 해커톤에서 ___ 를 준비하던 중 ___ 문제가 생겼다", "해커톤에서 서버가 멈췄다"],
    ))
    activity = {"name": "캠퍼스 해커톤", "type": "CONTEST", "role": "백엔드"}

    result, _ = _next(client, _turn(source_type="ACTIVITY", activity=activity))

    assert client.payloads[0]["activity"] == activity
    assert result.suggested_answers == ["캠퍼스 해커톤에서 ___ 를 준비하던 중 ___ 문제가 생겼다"]


def test_end_on_an_experience_card_carries_up_to_two_themes() -> None:
    """경험 카드에서 그만 묻기로 하면 고른 문항 유형을 겹치지 않게 두 개까지 보낸다."""
    client = FakeClient(QuestionOutput(action="END", themes=["GROWTH", "COLLABORATION", "GROWTH", "CONFLICT"]))

    result, _ = _next(client, _turn(previous_turns=ANSWERED_S))

    assert (result.next_action, result.themes) == ("COMPLETE", ["GROWTH", "COLLABORATION"])


def test_tech_card_end_has_no_themes() -> None:
    """기술 카드는 LLM 이 유형을 골라도 보내지 않는다."""
    client = FakeClient(QuestionOutput(action="END", themes=["GROWTH"]))

    result, _ = _next(client, _turn(card_kind="TECH", previous_turns=ANSWERED_S))

    assert (result.next_action, result.themes) == ("COMPLETE", [])


def test_card_themes_are_deduplicated_and_capped() -> None:
    """확정할 때 고른 문항 유형은 겹치지 않게 두 개까지 돌려준다."""
    client = FakeClient(ThemesOutput(themes=["LEADERSHIP", "LEADERSHIP", "CHALLENGE", "GROWTH"]))
    request = CardThemesRequest(card_id=7, title="동아리 회의 방식 바꾸기",
                                slots=[{"star_slot": "S", "body": "회의가 길었다"}])

    themes = asyncio.run(InterviewLLM(client).card_themes(request))

    assert themes == ["LEADERSHIP", "CHALLENGE"]
    assert client.payloads[0]["slots"] == [{"slot": "S", "text": "회의가 길었다"}]


def test_card_themes_endpoint_returns_503_when_unavailable(monkeypatch) -> None:
    """유형을 고르지 못하면 503 으로 알려 Spring 이 빈 유형으로 확정한다."""
    monkeypatch.setattr(interviews_api, "_llm", InterviewLLM(FakeClient(LLMStructuredCallError("실패"))))

    response = TestClient(app).post("/internal/card-themes", json={
        "card_id": 7, "title": "회의", "slots": [{"star_slot": "S", "body": "회의가 길었다"}],
    })

    assert response.status_code == 503


def test_activity_card_rejects_a_candidate() -> None:
    """활동 카드는 레포 후보가 없으므로 candidate 를 함께 보내면 거절한다."""
    with pytest.raises(ValidationError, match="candidate"):
        _turn(source_type="ACTIVITY", activity=ACTIVITY, candidate={"commits": []})


def test_polish_keeps_the_whole_answer_up_to_the_stored_length() -> None:
    """답은 저장 칸 길이(1,000자)까지 그대로 넘겨, 긴 답의 끝에만 있는 숫자도 지어낸 숫자로 보지 않는다."""
    answer = "회의 방식을 바꾸려고 여러 번 이야기를 나눴다. " * 30 + "그 결과 회의 시간이 40% 줄었다"
    client = FakeClient(SentenceOutput(sentence="회의 방식을 바꿔 회의 시간을 40% 줄였다."))

    result = _polish(client, answer)

    assert len(answer) > 500
    assert client.payloads[0]["answer"] == answer
    assert len(client.payloads) == 1
    assert result.unsourced_numbers == []


def test_activity_name_and_role_count_as_number_sources() -> None:
    """활동 이름·역할에 든 수치(24시간, 2개)는 지어낸 숫자가 아니라서, 그 수치가 든 빈칸 틀을 버리지 않는다."""
    client = FakeClient(QuestionOutput(
        action="ASK", star_slot="S", question="해커톤에서 어떤 상황이었나요?",
        suggested_answers=["24시간 캠퍼스 해커톤에서 ___ 를 준비하던 중 ___ 문제가 생겼다",
                           "2개 파트의 ___ 를 맡아 ___ 를 만들었다"],
    ))
    activity = {"name": "24시간 캠퍼스 해커톤", "type": "CONTEST", "role": "2개 파트 리드"}

    result, _ = _next(client, _turn(source_type="ACTIVITY", activity=activity))

    assert result.suggested_answers == ["24시간 캠퍼스 해커톤에서 ___ 를 준비하던 중 ___ 문제가 생겼다",
                                        "2개 파트의 ___ 를 맡아 ___ 를 만들었다"]


@pytest.mark.parametrize("overrides", [
    {"source_type": "ACTIVITY"},
    {"source_type": "DIRECT_CARD", "activity": ACTIVITY},
    {"source_type": "ACTIVITY", "activity": ACTIVITY, "card_kind": "TECH"},
], ids=["활동 출처인데 활동 없음", "활동 출처가 아닌데 활동 있음", "활동 카드인데 기술 카드"])
def test_source_activity_and_card_kind_must_agree(overrides: dict) -> None:
    """활동 출처일 때만 활동을 받고 활동 카드는 경험 카드뿐이라, 어긋난 조합은 400 이다."""
    payload = {"card_id": 7, "card_kind": "QUALITATIVE", "max_turns": 8,
               "missing_slots": [_slot("S"), _slot("T"), _slot("A"), _slot("R")], **overrides}

    response = TestClient(app).post("/internal/interview-turns", json=payload)

    assert response.status_code == 400
