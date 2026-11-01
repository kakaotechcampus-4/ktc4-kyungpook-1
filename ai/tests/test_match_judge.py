"""매칭 2단계 조건 판단: 조건마다 채우는 카드를 모두 돌려주고, 규칙에 맞지 않는 답은 한 번 더 묻고, 못 받으면 503 이다."""

from __future__ import annotations

import asyncio
import threading
from typing import Any

import pytest
from fastapi.testclient import TestClient

import api.match_judgements as match_judgements_api
from main import app
from schemas.match_judgement import MatchJudgementRequest
from services.llm_settings import LLMConfigError
from services.llm_structured_client import (
    LLMInvalidOutputError,
    LLMNonRetryableError,
    LLMStructuredCallError,
    StructuredCallResult,
)
from services.match_judge import JudgeOutput, MatchJudge, _Judgement, _Support

PATH = "/internal/matching/judgements"


class QueuedClient:
    """정해 둔 답(또는 예외)을 차례로 돌려주고, 받은 요청을 남긴다."""

    def __init__(self, *answers: Any) -> None:
        self._answers = list(answers)
        self._lock = threading.Lock()
        self.payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        with self._lock:
            self.payloads.append(payload)
            answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _request(**overrides: Any) -> dict:
    payload = {
        "company_name": "에이크미", "job_title": "백엔드 개발자",
        "conditions": [
            {"condition_id": "c1", "criterion_type": "REQUIRED", "text": "Spring 으로 API 를 개발한 경험"},
            {"condition_id": "c2", "criterion_type": "PREFERRED", "text": "대용량 트래픽 처리 경험"},
        ],
        "cards": [
            {"card_id": "k1", "title": "결제 API", "action": "Spring Boot 로 결제 승인 API 를 만들었다",
             "techs": ["JAVA", "SPRING"]},
            {"card_id": "k2", "title": "캐시 도입", "result": "조회 응답을 300ms 에서 40ms 로 줄였다"},
        ],
    }
    return payload | overrides


def _answer(*judgements: tuple[str, list[tuple[str, str]]]) -> JudgeOutput:
    return JudgeOutput(judgements=[
        _Judgement(condition_id=condition_id, met_by=[_Support(card_id=card_id, reason=reason) for card_id, reason in met_by])
        for condition_id, met_by in judgements
    ])


VALID = _answer(("c1", [("k1", "Spring Boot 로 결제 승인 API 를 만들었다")]), ("c2", []))


def _post(monkeypatch: pytest.MonkeyPatch, client: QueuedClient, payload: dict) -> dict:
    monkeypatch.setattr(match_judgements_api, "_judge", MatchJudge(client))
    response = TestClient(app).post(PATH, json=payload)
    return {"status": response.status_code, **response.json()}


def test_returns_supporting_cards_for_every_condition(monkeypatch: pytest.MonkeyPatch) -> None:
    """조건마다 채우는 카드와 이유를 돌려주고, 채우는 카드가 없는 조건도 빈 목록으로 남긴다."""
    body = _post(monkeypatch, QueuedClient(VALID), _request())

    assert body["status"] == 200
    assert body["data"]["judgements"] == [
        {"condition_id": "c1", "met_by": [{"card_id": "k1", "reason": "Spring Boot 로 결제 승인 API 를 만들었다"}]},
        {"condition_id": "c2", "met_by": []},
    ]
    assert body["meta"]["ended_by"] == "COMPLETED"


@pytest.mark.parametrize("payload", [
    _request(conditions=[{"condition_id": "c1", "criterion_type": "RESPONSIBILITY", "text": "결제 서버 운영"}]),
    _request(conditions=[{"condition_id": "c1", "criterion_type": "REQUIRED", "text": "Java"},
                         {"condition_id": "c1", "criterion_type": "PREFERRED", "text": "Kotlin"}]),
    _request(cards=[{"card_id": "k1", "title": "가"}, {"card_id": "k1", "title": "나"}]),
    _request(conditions=[{"condition_id": f"c{i}", "criterion_type": "REQUIRED", "text": "Java"} for i in range(31)]),
    _request(cards=[{"card_id": f"k{i}", "title": "카드"} for i in range(41)]),
], ids=["담당 업무", "겹친 조건 id", "겹친 카드 id", "조건 31개", "카드 41장"])
def test_rejects_requests_outside_the_contract(monkeypatch: pytest.MonkeyPatch, payload: dict) -> None:
    """담당 업무 조건, 겹친 id, 조건 30개·카드 40장을 넘는 요청은 LLM 을 부르지 않고 400 이다."""
    client = QueuedClient()

    body = _post(monkeypatch, client, payload)

    assert body["status"] == 400
    assert client.payloads == []


def test_clips_and_masks_card_text_before_sending() -> None:
    """카드 칸은 400자까지만 보내고, 비밀값·이메일은 가린다."""
    client = QueuedClient(VALID)
    long_action = "a@example.com 으로 알림을 보냈다 " + "가" * 1_000
    request = MatchJudgementRequest.model_validate(_request(cards=[
        {"card_id": "k1", "title": "결제 API", "action": long_action}, {"card_id": "k2", "title": "캐시 도입"}]))

    asyncio.run(MatchJudge(client).judge(request))

    sent = client.payloads[0]["cards"][0]["action"]
    assert len(sent) == 400
    assert "a@example.com" not in sent


@pytest.mark.parametrize("first", [
    _answer(("c1", [])),
    _answer(("c1", []), ("c2", []), ("c3", [])),
    _answer(("c1", []), ("c1", []), ("c2", [])),
    _answer(("c1", [("k1", "결제 API"), ("k1", "결제 API")]), ("c2", [])),
    _answer(("c1", [("k9", "없는 카드")]), ("c2", [])),
    _answer(("c1", [("k1", " ")]), ("c2", [])),
    _answer(("c1", [("k1", "가" * 81)]), ("c2", [])),
    LLMInvalidOutputError("출력 모양이 틀림"),
], ids=["조건 빠짐", "보내지 않은 조건", "조건 겹침", "카드 겹침", "보내지 않은 카드", "빈 이유", "긴 이유", "모양 틀림"])
def test_asks_again_when_the_answer_breaks_the_rules(monkeypatch: pytest.MonkeyPatch, first: Any) -> None:
    """규칙이나 모양이 틀린 답은 버리고 한 번 더 물어 맞는 답을 쓴다."""
    client = QueuedClient(first, VALID)

    body = _post(monkeypatch, client, _request())

    assert body["status"] == 200
    assert len(client.payloads) == 2
    assert body["data"]["judgements"][0]["met_by"][0]["card_id"] == "k1"


@pytest.mark.parametrize("answers, retryable, calls", [
    ((_answer(("c1", [])), _answer(("c1", []))), True, 2),
    ((LLMStructuredCallError("호출 실패"),), True, 1),
    ((LLMConfigError("설정 없음"),), False, 1),
    ((LLMNonRetryableError("콘텐츠 필터"),), False, 1),
], ids=["두 번 다 규칙 위반", "호출 실패", "설정 없음", "다시 해도 안 되는 실패"])
def test_returns_503_when_no_valid_answer_arrives(monkeypatch: pytest.MonkeyPatch, answers: tuple,
                                                  retryable: bool, calls: int) -> None:
    """맞는 답을 받지 못하면 503 LLM_UNAVAILABLE 이고, 설정 누락처럼 다시 해도 안 되는 실패만 retryable=false 다."""
    client = QueuedClient(*answers)

    body = _post(monkeypatch, client, _request())

    assert body["status"] == 503
    assert (body["error"]["code"], body["error"]["retryable"]) == ("LLM_UNAVAILABLE", retryable)
    assert len(client.payloads) == calls
