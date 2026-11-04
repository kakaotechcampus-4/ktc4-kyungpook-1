"""되묻기 질문: 빈칸 틀을 내지 않고, 사용자가 한 말을 그대로 되풀이하는 질문은 한 번 더 묻는지 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Optional

import pytest

from schemas.interview import InterviewTurnRequest
from services.interview_agent import InterviewAgent
from services.interview_llm import QUESTION_PROMPT, InterviewLLM, QuestionOutput, repeats_previous
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult


class FakeClient:
    """정해 둔 답(또는 예외)을 차례로 돌려주고, 받은 입력을 기록한다."""

    def __init__(self, *answers: Any) -> None:
        self._answers = list(answers)
        self.payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.payloads.append(payload)
        answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


ANSWER = "리뷰 체크리스트를 만들어서 같이 썼어요"
SITUATION = "신입 부원이 리뷰를 어려워해서 PR 이 쌓였어요"
ACTIVITY = {"name": "교내 개발 동아리", "type": "CLUB", "role": "백엔드 파트장"}
COMMITS = {"commits": [{"commit_id": 1, "sha": "a" * 40, "message": "docs: 리뷰 체크리스트 추가"}]}
SOURCES = {
    "직접 쓴 카드": {"source_type": "DIRECT_CARD"},
    "활동 카드": {"source_type": "ACTIVITY", "activity": ACTIVITY},
    "커밋 묶음": {"source_type": "COMMIT_CLUSTER", "candidate": COMMITS},
}


def _slot(slot: str, body: Optional[str] = None) -> dict:
    return {"star_slot": slot, "statement_seq": 1, "body": body, "confidence": "LOW"}


def _request(**overrides: Any) -> InterviewTurnRequest:
    payload = {
        "card_id": 7, "source_type": "DIRECT_CARD", "card_kind": "QUALITATIVE", "max_turns": 8,
        "missing_slots": [_slot("S", SITUATION), _slot("A")],
        "allowed_slots": ["A"],
        "previous_turns": [{"star_slot": "S", "question_text": "어떤 상황이었나요?", "outcome": "ANSWERED",
                            "answer_text": ANSWER}],
    }
    payload.update(overrides)
    return InterviewTurnRequest.model_validate(payload)


def _ask(question: str = "그 뒤에 팀원들은 뭐라고 했나요?", suggestions: Optional[list[str]] = None) -> QuestionOutput:
    return QuestionOutput(action="ASK", star_slot="A", question=question, suggested_answers=suggestions or [])


def _next(client: FakeClient, request: InterviewTurnRequest):
    return asyncio.run(InterviewAgent(llm=InterviewLLM(client)).next_turn(request))


@pytest.mark.parametrize("overrides", list(SOURCES.values()), ids=list(SOURCES))
def test_candidates_with_a_blank_are_dropped_for_every_source(overrides: dict) -> None:
    """빈칸(___)이 든 후보는 출처가 무엇이든 버린다."""
    client = FakeClient(_ask(suggestions=["동아리에서 ___ 때문에 ___ 를 했다"]))

    result, _ = _next(client, _request(**overrides))

    assert result.suggested_answers == []


@pytest.mark.parametrize("overrides", [SOURCES["직접 쓴 카드"], SOURCES["활동 카드"]], ids=["직접 쓴 카드", "활동 카드"])
def test_record_less_cards_keep_candidates_without_a_blank(overrides: dict) -> None:
    """읽을 기록이 없는 카드도 앞 답을 완성된 문장으로 옮긴 후보는 남긴다."""
    client = FakeClient(_ask(suggestions=["리뷰 체크리스트를 만들어 같이 썼다"]))

    result, _ = _next(client, _request(**overrides))

    assert result.suggested_answers == ["리뷰 체크리스트를 만들어 같이 썼다"]


@pytest.mark.parametrize(("question", "overrides"), [
    (f"'{ANSWER}' 이후에 뭐가 달라졌나요?", {}),
    (f"'{SITUATION[:14]}' 에서 누가 먼저 도왔나요?", {"missing_slots": [_slot("S"), _slot("A", SITUATION)]}),
], ids=["앞 답", "묻는 칸의 지금 문장"])
def test_question_repeating_the_users_words_is_asked_again(question: str, overrides: dict) -> None:
    """앞 답이나 묻는 칸의 지금 문장 12자 구절이 질문에 그대로 들어 있으면, 같은 입력으로 한 번 더 묻고 다시 받은 질문을 쓴다."""
    fresh = _ask()
    client = FakeClient(_ask(question), fresh)

    result, fell_back = _next(client, _request(**overrides))

    assert len(client.payloads) == 2 and client.payloads[0] == client.payloads[1]
    assert (result.question_text, fell_back) == (fresh.question, False)


def test_second_repeat_is_used_as_it_is_with_a_warning(caplog) -> None:
    """두 번째 질문도 되풀이면 틀 질문으로 바꾸거나 실패로 만들지 않고 그대로 쓰며, 사용자 글 없이 경고만 남긴다."""
    again = _ask(f"'{ANSWER}' 를 하고 나서는요?")
    client = FakeClient(_ask(f"'{ANSWER}' 이후에 뭐가 달라졌나요?"), again)

    with caplog.at_level(logging.WARNING, logger="services.interview_llm"):
        result, fell_back = _next(client, _request())

    assert len(client.payloads) == 2
    assert (result.next_action, result.question_text, fell_back) == ("ASK_AGAIN", again.question, False)
    assert [record.levelno for record in caplog.records if record.name == "services.interview_llm"] == [logging.WARNING]
    assert ANSWER not in caplog.text


def test_repeated_question_is_kept_when_asking_again_fails() -> None:
    """다시 묻는 호출이 실패하면 처음 받은 질문을 그대로 쓴다."""
    first = _ask(f"'{ANSWER}' 이후에 뭐가 달라졌나요?")
    client = FakeClient(first, LLMStructuredCallError("실패"))

    result, fell_back = _next(client, _request())

    assert (result.next_action, result.question_text, fell_back) == ("ASK_AGAIN", first.question, False)


def test_short_or_punctuation_only_overlap_is_not_a_repeat() -> None:
    """겹침은 12자부터 되풀이이고, 12자보다 짧은 겹침이나 공백·문장부호만인 겹침은 아니다."""
    assert repeats_previous(f"'{ANSWER[:12]}' 이후에는요?", [ANSWER])
    assert not repeats_previous(f"'{ANSWER[:11]}' 이후에는요?", [ANSWER])
    assert not repeats_previous("그래서요 ?!?!?!?!?!?!?!", ["?!?!?!?!?!?!?!"])
    assert not repeats_previous("그래서요 - - - - - - - - - -", ["- - - - - - - - - -"])


def test_repeat_check_folds_whitespace() -> None:
    """줄바꿈이나 띄어쓰기 개수만 달라도 같은 구절이면 되풀이다."""
    assert repeats_previous("리뷰  체크리스트를\n만들어서 같이 쓴 뒤에는요?", [ANSWER])
    assert repeats_previous("리뷰 체크리스트를 만들어서 같이 쓴 뒤에는요?", ["리뷰   체크리스트를\n만들어서 같이 썼어요"])


def test_prompt_has_no_blank_template_sentence() -> None:
    """프롬프트에는 빈칸 틀을 쓰라는 문장이 없고, 쓰지 말라는 문장만 있다."""
    assert "빈칸 틀을 씁니다" not in QUESTION_PROMPT
    assert QUESTION_PROMPT.count("___") == 1
    assert "사용자가 채울 빈칸(___)이 있는 틀은 쓰지 않습니다." in QUESTION_PROMPT
