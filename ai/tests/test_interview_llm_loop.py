"""되묻기 루프: 고를 수 있는 칸 안에서 LLM 이 다음 칸·질문·예상 답변을 정하고, 규칙은 코드가 지킨다."""

from __future__ import annotations

import asyncio
import logging
import threading
from typing import Any, Optional

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import api.interviews as interviews_api
from main import app
from schemas.interview import InterviewTurnRequest, MeasuredSentenceRequest
from services.interview_agent import InterviewAgent
from services.interview_llm import (
    InterviewLLM,
    InterviewLLMLimits,
    InterviewLLMUnavailable,
    QuestionOutput,
    SentenceOutput,
)
from services.llm_settings import LLMConfigError
from services.llm_structured_client import (
    LLMInvalidOutputError,
    LLMStructuredCallError,
    StructuredCallResult,
)


class FakeClient:
    """정해 둔 답(또는 예외)을 차례로 돌려주고, 받은 입력과 실행 스레드를 기록한다."""

    def __init__(self, *answers: Any) -> None:
        self._answers = list(answers)
        self.payloads: list[dict[str, Any]] = []
        self.threads: list[str] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.payloads.append(payload)
        self.threads.append(threading.current_thread().name)
        answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _slot(slot: str, body: Optional[str] = None, seq: int = 1) -> dict:
    return {"star_slot": slot, "statement_seq": seq, "body": body, "confidence": "LOW"}


def _request(**overrides: Any) -> InterviewTurnRequest:
    payload = {
        "card_id": 7,
        "source_type": "DIRECT_CARD",
        "max_turns": 3,
        "missing_slots": [_slot("S", None, 1), _slot("R", "목록 조회를 쿼리 한 번으로 바꿨다", 4)],
    }
    payload.update(overrides)
    return InterviewTurnRequest.model_validate(payload)


def _ask(slot: str, question: str = "그때 어떤 문제가 있었나요?", mode: str = "TEXT",
         suggestions: Optional[list[str]] = None) -> QuestionOutput:
    return QuestionOutput(action="ASK", star_slot=slot, question=question, answer_mode=mode,
                          suggested_answers=suggestions or [])


def _agent(client: Optional[FakeClient], limits: Optional[InterviewLLMLimits] = None) -> InterviewAgent:
    return InterviewAgent(llm=None if client is None else InterviewLLM(client, limits))


def _next(agent: InterviewAgent, request: InterviewTurnRequest):
    return asyncio.run(agent.next_turn(request))


def test_cap_reached_completes_without_calling_llm() -> None:
    """질문 상한을 다 쓰면 LLM 을 부르지 않고 끝낸다."""
    client = FakeClient()

    result, used_llm = _next(_agent(client), _request(existing_turn_count=3))

    assert result.next_action == "COMPLETE"
    assert (used_llm, client.payloads) == (False, [])


def test_no_choosable_slot_completes_without_calling_llm() -> None:
    """고를 수 있는 칸이 없으면 LLM 을 부르지 않고 끝낸다."""
    client = FakeClient()

    result, _ = _next(_agent(client), _request(allowed_slots=[]))

    assert result.next_action == "COMPLETE"
    assert client.payloads == []


def test_first_question_asks_r_when_r_has_no_metric() -> None:
    """첫 질문은 R 에 확인된 수치가 없으면 R 로 정해 LLM 에 넘긴다."""
    client = FakeClient(_ask("R", mode="MEASURE"))

    result, _ = _next(_agent(client), _request())

    assert client.payloads[0]["fixed_slot"] == "R"
    assert (result.target_star_slot, result.target_statement_seq) == ("R", 4)


def test_first_question_lets_llm_choose_when_r_already_has_metric() -> None:
    """R 에 이미 수치가 있으면 첫 칸도 LLM 이 고른다."""
    client = FakeClient(_ask("S"))
    request = _request(missing_slots=[_slot("S"), _slot("R", "조회 시간을 3초에서 0.4초로 줄였다", 4)])

    _next(_agent(client), request)

    assert client.payloads[0]["fixed_slot"] is None


def test_target_slot_is_asked_as_given() -> None:
    """화면에서 칸을 지정하면 그 칸을 정해진 칸으로 넘긴다."""
    client = FakeClient(_ask("S"))

    result, _ = _next(_agent(client), _request(target_star_slot="S"))

    assert client.payloads[0]["fixed_slot"] == "S"
    assert result.target_star_slot == "S"


def test_llm_can_end_when_no_slot_is_fixed() -> None:
    """정해진 칸이 없을 때 LLM 이 끝을 고르면 질문 없이 끝낸다."""
    client = FakeClient(QuestionOutput(action="END"))
    previous = [{"star_slot": "S", "question_text": "상황은요?", "outcome": "ANSWERED", "answer_text": "배포가 느렸다"}]

    result, used_llm = _next(_agent(client), _request(previous_turns=previous))

    assert (result.next_action, used_llm) == ("COMPLETE", True)


def test_end_on_fixed_slot_falls_back_to_template_question() -> None:
    """칸이 정해졌는데 LLM 이 끝을 고르면 규칙 위반이라 다시 묻고, 또 틀리면 틀 질문을 쓴다."""
    client = FakeClient(QuestionOutput(action="END"), QuestionOutput(action="END"))

    result, _ = _next(_agent(client), _request(target_star_slot="S"))

    assert len(client.payloads) == 2
    assert (result.next_action, result.target_star_slot, result.suggested_answers) == ("ASK_AGAIN", "S", [])


def test_slot_outside_allowed_is_retried_once() -> None:
    """고를 수 없는 칸을 고르면 한 번 더 요청하고, 맞게 고르면 그 질문을 쓴다."""
    client = FakeClient(_ask("T"), _ask("S"))
    previous = [{"star_slot": "R", "question_text": "결과는요?", "outcome": "NOT_MEASURED", "answer_text": None}]

    result, _ = _next(_agent(client), _request(allowed_slots=["S"], previous_turns=previous))

    assert len(client.payloads) == 2
    assert result.target_star_slot == "S"


def test_measure_mode_is_only_kept_for_r() -> None:
    """R 이 아닌 칸에 MEASURE 가 오면 TEXT 로 고친다."""
    client = FakeClient(_ask("S", mode="MEASURE"))

    result, _ = _next(_agent(client), _request(target_star_slot="S"))

    assert result.answer_mode == "TEXT"


def test_suggestions_drop_invented_metrics_and_keep_two() -> None:
    """입력에 없는 수치가 든 예상 답변은 버리고, 중복을 빼고 2개까지만 남긴다."""
    suggestions = ["응답 시간을 50% 줄였다", "배포 대기를 없앴다", "배포 대기를 없앴다",
                   "목록 조회를 쿼리 한 번으로 바꿨다", "반복 조회를 없앴다"]
    client = FakeClient(_ask("S", suggestions=suggestions))

    result, _ = _next(_agent(client), _request(target_star_slot="S"))

    assert result.suggested_answers == ["배포 대기를 없앴다", "목록 조회를 쿼리 한 번으로 바꿨다"]


@pytest.mark.parametrize("error", [LLMStructuredCallError("실패"), LLMConfigError("설정 없음")])
def test_llm_failure_falls_back_to_template_question(error: Exception) -> None:
    """LLM 호출이 실패하거나 설정이 없으면 틀 질문으로 대신해 질문 요청이 실패하지 않는다."""
    result, _ = _next(_agent(FakeClient(error)), _request())

    assert (result.next_action, result.target_star_slot, result.answer_mode) == ("ASK_AGAIN", "R", "MEASURE")
    assert result.question_text


def test_llm_failure_log_has_no_exception_text(caplog) -> None:
    """실패 로그에는 예외 종류만 남기고 예외 문구·traceback 을 싣지 않는다(#421)."""
    error = LLMStructuredCallError("LEAK-모델-출력")
    error.__cause__ = ValueError("LEAK-모델-출력")

    with caplog.at_level(logging.WARNING):
        _next(_agent(FakeClient(error)), _request())

    records = [record for record in caplog.records if record.name == "services.interview_llm"]
    assert len(records) == 1
    assert "exc=LLMStructuredCallError cause=ValueError" in records[0].getMessage()
    assert "LEAK" not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


def test_invalid_output_is_requested_again() -> None:
    """형식이 틀린 답은 한 번 더 요청한다."""
    client = FakeClient(LLMInvalidOutputError("형식"), _ask("R"))

    result, _ = _next(_agent(client), _request())

    assert len(client.payloads) == 2
    assert result.question_text == "그때 어떤 문제가 있었나요?"


def test_too_little_time_skips_llm_and_uses_template() -> None:
    """마감까지 남은 시간이 최소 호출 시간보다 짧으면 LLM 을 부르지 않는다."""
    client = FakeClient()

    result, _ = _next(_agent(client, InterviewLLMLimits(deadline_seconds=1, min_call_seconds=4)), _request())

    assert client.payloads == []
    assert result.next_action == "ASK_AGAIN"


def test_same_slot_asked_again_is_a_followup() -> None:
    """앞에서 물은 칸을 다시 물으면 FOLLOWUP 질문이다."""
    client = FakeClient(_ask("S"))
    previous = [{"star_slot": "S", "question_text": "상황은요?", "outcome": "ANSWERED", "answer_text": "느렸다"}]

    result, _ = _next(_agent(client), _request(previous_turns=previous))

    assert result.question_type == "FOLLOWUP"


def test_question_llm_runs_on_interview_executor() -> None:
    """되묻기 LLM 은 카드 초안 줄이 아니라 되묻기 전용 실행기에서 부른다."""
    client = FakeClient(_ask("R"))

    _next(_agent(client), _request())

    assert client.threads[0].startswith("llm-interview")


@pytest.mark.parametrize("overrides", [
    {"allowed_slots": ["T"]},
    {"allowed_slots": ["S", "S"]},
    {"allowed_slots": ["S"], "target_star_slot": "R"},
])
def test_slot_rules_reject_inconsistent_request(overrides: dict) -> None:
    """고를 수 있는 칸이 missing_slots 에 없거나 겹치거나, 지정 칸이 고를 수 없는 칸이면 받지 않는다."""
    with pytest.raises(ValidationError):
        _request(**overrides)


def _sentence_request() -> MeasuredSentenceRequest:
    return MeasuredSentenceRequest(card_id=7, card_title="목록 조회 개선",
                                   current_result="목록 조회를 쿼리 한 번으로 바꿨다", user_text="3초 걸리던 게 0.4초 정도")


def test_measured_sentence_is_written_from_user_text() -> None:
    """수치 문장은 LLM 이 쓴 문장을 앞뒤 공백만 지워 돌려준다."""
    client = FakeClient(SentenceOutput(sentence=" 조회 시간을 3초에서 0.4초로 줄였다. "))

    sentence = asyncio.run(InterviewLLM(client).write_sentence(_sentence_request()))

    assert sentence == "조회 시간을 3초에서 0.4초로 줄였다."
    assert client.payloads[0]["user_text"] == "3초 걸리던 게 0.4초 정도"


def test_blank_measured_sentence_is_unavailable() -> None:
    """빈 문장만 받으면 쓸 만한 결과가 없다고 알린다."""
    client = FakeClient(SentenceOutput(sentence=" "), SentenceOutput(sentence=""))

    with pytest.raises(InterviewLLMUnavailable):
        asyncio.run(InterviewLLM(client).write_sentence(_sentence_request()))


def test_measured_sentence_endpoint_returns_503_when_unavailable(monkeypatch) -> None:
    """수치 문장을 받지 못하면 503 LLM_UNAVAILABLE 로 알려 Spring 이 틀 문장으로 대신한다."""
    monkeypatch.setattr(interviews_api, "_llm", InterviewLLM(FakeClient(LLMStructuredCallError("실패"))))

    response = TestClient(app).post("/internal/interview-measured-sentence", json=_sentence_request().model_dump())

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "LLM_UNAVAILABLE"
