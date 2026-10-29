"""자소서 API: 공통 응답 형식으로 문항 순서대로 돌려주고, 요청 규칙을 어기면 400 INVALID_PAYLOAD 다."""

from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

import api.cover_letters as cover_letters_api
from main import app
from services.cover_letter_asker import AskItemOutput, AskOutput, CoverLetterAsker
from services.cover_letter_assigner import ConsideredOutput, CoverLetterAssigner, PickOutput
from services.cover_letter_drafter import CoverLetterDrafter, ReviewOutput
from services.cover_letter_llm import CoverLetterLimits
from services.cover_letter_writer import WriteOutput
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

SUMMARY = {"card_id": 1, "title": "배포 시간 단축", "summary": "배포 시간을 40분에서 12분으로 줄였다"}
CARD = {
    "card_id": 1, "title": "배포 시간 단축",
    "statements": [{"star_slot": "S", "text": "배포 한 번에 40분이 걸렸다"},
                   {"star_slot": "A", "text": "바뀐 모듈만 다시 빌드했다"}],
}


class FixedClient:
    """출력 모델마다 정해 둔 답을 돌려주고, 문항 문장으로 나눠 둔 답이면 그 문항의 답을 쓴다."""

    def __init__(self, answers: dict[type, Any]) -> None:
        self._answers = answers

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        answer = self._answers[output_model]
        if isinstance(answer, dict):
            answer = answer[payload["question"]["text"]]
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _post(path: str, payload: dict) -> dict:
    response = TestClient(app).post(path, json=payload)
    return {"status": response.status_code, **response.json()}


def test_assignment_api_returns_result_and_how_it_ended(monkeypatch: pytest.MonkeyPatch) -> None:
    """카드 고르기 API 는 배정과 끝난 방식을 공통 응답 형식으로 돌려준다."""
    pick = PickOutput(question_theme="PROBLEM_SOLVING", considered=[ConsideredOutput(card_id=1, note="원인을 고친 과정")],
                      first_card_id=1, first_reason="문제 해결 과정이 있음")
    client = FixedClient({PickOutput: pick})
    monkeypatch.setattr(cover_letters_api, "_assigner", CoverLetterAssigner(client, client))
    payload = {"cover_letter_id": 9, "job_role": "백엔드 개발", "cards": [SUMMARY],
               "questions": [{"question_id": 1, "text": "문제 해결 경험", "char_limit": 700}]}

    body = _post("/internal/cover-letter-assignments", payload)

    assert body["status"] == 200
    assert (body["data"]["assignments"][0]["primary_card_id"], body["meta"]["ended_by"]) == (1, "COMPLETED")


def test_duplicate_card_ids_are_rejected() -> None:
    """같은 카드가 두 번 오면 어느 카드인지 정할 수 없어 400 INVALID_PAYLOAD 다."""
    payload = {"cover_letter_id": 9, "job_role": "백엔드 개발", "cards": [SUMMARY, SUMMARY],
               "questions": [{"question_id": 1, "text": "문제 해결 경험", "char_limit": 700}]}

    body = _post("/internal/cover-letter-assignments", payload)

    assert (body["status"], body["error"]["code"]) == (400, "INVALID_PAYLOAD")


def test_questions_api_returns_asks_in_request_order(monkeypatch: pytest.MonkeyPatch) -> None:
    """질문 API 는 문항 순서대로 질문을 돌려주고, 일부 문항만 틀 질문이면 PARTIAL 이다."""
    asks = {"문제 해결 경험": AskOutput(asks=[AskItemOutput(item="RESULT", question="결과로 무엇이 달라졌나요?")]),
            "지원 동기": LLMStructuredCallError("실패")}
    monkeypatch.setattr(cover_letters_api, "_asker",
                        CoverLetterAsker(FixedClient({AskOutput: asks}), CoverLetterLimits(target_ratio=0.0)))
    payload = {"cover_letter_id": 9, "job_role": "백엔드 개발", "questions": [
        {"question_id": 1, "text": "문제 해결 경험", "char_limit": 700, "question_theme": "PROBLEM_SOLVING",
         "primary_card": CARD},
        {"question_id": 2, "text": "지원 동기", "char_limit": 500, "question_theme": "MOTIVATION"},
    ]}

    body = _post("/internal/cover-letter-questions", payload)

    assert [(q["question_id"], q["source"]) for q in body["data"]["questions"]] == [(1, "LLM"), (2, "TEMPLATE")]
    assert body["meta"]["ended_by"] == "PARTIAL"


def test_supporting_card_without_primary_is_rejected() -> None:
    """중심 카드 없이 보조 카드만 오면 400 INVALID_PAYLOAD 다."""
    payload = {"cover_letter_id": 9, "job_role": "백엔드 개발", "questions": [
        {"question_id": 1, "text": "문제 해결 경험", "char_limit": 700, "supporting_card": CARD},
    ]}

    body = _post("/internal/cover-letter-questions", payload)

    assert (body["status"], body["error"]["code"]) == (400, "INVALID_PAYLOAD")


def test_drafts_api_returns_drafts_in_request_order(monkeypatch: pytest.MonkeyPatch) -> None:
    """초안 API 는 요청 문항 순서대로 초안을 돌려준다."""
    writes = {"문제 해결 경험": WriteOutput(text="바뀐 모듈만 다시 빌드했습니다. 배포가 40분 걸렸습니다."),
              "협업 경험": WriteOutput(text="바뀐 모듈만 다시 빌드하자고 제안했습니다. 배포가 40분 걸렸습니다.")}
    client = FixedClient({WriteOutput: writes, ReviewOutput: ReviewOutput(findings=[])})
    monkeypatch.setattr(cover_letters_api, "_drafter",
                        CoverLetterDrafter(client, client, CoverLetterLimits(target_ratio=0.0)))
    question = {"char_limit": 700, "question_theme": "PROBLEM_SOLVING", "primary_card": CARD,
                "answers": [{"item": "RESULT", "text": "배포가 빨라졌다", "card_id": 1}]}
    payload = {"cover_letter_id": 9, "job_role": "백엔드 개발", "questions": [
        {**question, "question_id": 1, "text": "문제 해결 경험"},
        {**question, "question_id": 2, "text": "협업 경험"},
    ]}

    body = _post("/internal/cover-letter-drafts", payload)

    assert [d["question_id"] for d in body["data"]["drafts"]] == [1, 2]
    assert body["meta"]["ended_by"] == "COMPLETED"


def test_duplicate_missing_items_are_rejected() -> None:
    """같은 항목이 빈칸 목록에 두 번 오면 400 INVALID_PAYLOAD 다."""
    payload = {"cover_letter_id": 9, "job_role": "백엔드 개발", "questions": [
        {"question_id": 1, "text": "문제 해결 경험", "char_limit": 700, "missing_items": ["RESULT", "RESULT"]},
    ]}

    body = _post("/internal/cover-letter-drafts", payload)

    assert (body["status"], body["error"]["code"]) == (400, "INVALID_PAYLOAD")
