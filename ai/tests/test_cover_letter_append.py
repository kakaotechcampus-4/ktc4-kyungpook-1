"""이미 쓴 글에 새 카드 내용 덧붙이기: 원래 문장은 그대로 두고 새 문장만 더하며, 원래 문장이 바뀌면 앞 글로 되돌린다."""

from __future__ import annotations

import asyncio
from typing import Any, Optional

import pytest
from fastapi.testclient import TestClient

import api.cover_letters as cover_letters_api
from main import app
from schemas.cover_letter import AppendCardRequest
from services.cover_letter_append import AppendPlan, CoverLetterAppender, Insertion
from services.cover_letter_drafter import ReviewOutput
from services.llm_structured_client import StructuredCallResult

QID = 4
FIRST = "주문 조회가 느리다는 지적을 받고 백엔드 역량이 부족하다고 느꼈습니다."
SECOND = "그 뒤 주문 목록 조회를 한 번에 묶어 가져오게 바꿨습니다."
CURRENT = f"{FIRST}\n{SECOND}"
ADDED = "캐시 적중률을 올려 같은 조회를 반복하지 않게 했습니다."
PRIMARY = {
    "card_id": 7, "title": "주문 조회 성능 개선", "source_name": "shop",
    "statements": [{"star_slot": "A", "text": "주문 목록 조회를 한 번에 묶어 가져오게 바꿨다"}],
}
SUPPORTING = {
    "card_id": 9, "title": "조회 캐시 도입", "source_name": "shop",
    "statements": [{"star_slot": "A", "text": "캐시 적중률을 올려 같은 조회를 반복하지 않게 했다"}],
}


class AppendClient:
    """덧붙일 자리 계획(AppendPlan)을 돌려주고 받은 요청을 남긴다. 감독 답은 걸린 문장이 없다."""

    def __init__(self, *insertions: Insertion) -> None:
        self._plan = AppendPlan(insertions=list(insertions))
        self.payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        if output_model is ReviewOutput:
            output = ReviewOutput(findings=[])
        else:
            self.payloads.append(payload)
            output = self._plan
        return StructuredCallResult(output=output, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _payload(current: str = CURRENT, char_limit: int = 700, **extra: Any) -> dict:
    return {
        "cover_letter_id": 12, "job_role": "백엔드 개발", "memo": None, "posting": None, "others": [],
        "target": {"question_id": QID, "text": "직무 역량을 키우기 위해 노력한 경험을 적어 주세요", "char_limit": char_limit,
                   "question_theme": "GROWTH", "primary_card": PRIMARY, "supporting_card": SUPPORTING,
                   "answers": [], "current_text": current, "inferred_seqs": []},
        "added_card_id": 9, **extra,
    }


def _append(client: AppendClient, payload: Optional[dict] = None):
    return asyncio.run(CoverLetterAppender(client, client).append(AppendCardRequest.model_validate(payload or _payload())))


def _reasons(result) -> list:
    return [step.reason for step in result.steps if step.kind == "REVERTED"]


def test_append_inserts_after_the_given_sentence_and_keeps_original_sentences() -> None:
    """원래 문장은 글자 그대로 두고, 정한 문장 뒤에 새 카드 문장을 끼우며, 쓰기 AI 에는 번호 붙인 문장과 새 카드를 넘긴다."""
    client = AppendClient(Insertion(after_sentence_seq=1, sentences=[ADDED]))

    result = _append(client)

    assert result.draft.text == f"{FIRST}\n{ADDED}\n{SECOND}"
    assert [s["seq"] for s in client.payloads[0]["sentences"]] == [1, 2]
    assert client.payloads[0]["new_card"]["title"] == "조회 캐시 도입"
    assert _reasons(result) == []


def test_position_zero_inserts_before_the_first_sentence() -> None:
    """자리 번호 0 은 첫 문장 앞이다."""
    result = _append(AppendClient(Insertion(after_sentence_seq=0, sentences=[ADDED])))

    assert result.draft.text == f"{ADDED}\n{FIRST}\n{SECOND}"


def test_append_reverts_when_the_plan_adds_nothing() -> None:
    """더할 문장이 없으면 덧붙인 게 아니라 앞 글로 되돌리고 까닭을 남긴다."""
    result = _append(AppendClient())

    assert result.draft.text == CURRENT
    assert _reasons(result) == ["더한 문장이 없어 되돌렸다"]


def test_position_outside_the_text_reverts() -> None:
    """글에 없는 문장 번호 뒤에 끼우라면 자리를 못 찾아 앞 글로 되돌린다."""
    result = _append(AppendClient(Insertion(after_sentence_seq=3, sentences=[ADDED])))

    assert result.draft.text == CURRENT
    assert _reasons(result) == ["끼울 자리를 못 찾아 되돌렸다"]


def test_last_inserted_sentence_is_dropped_when_over_the_limit() -> None:
    """글자 수 한도를 넘으면 더한 문장을 뒤에서부터 덜어 내고, 남은 것으로 한도 안이면 그대로 쓴다."""
    limit = len(CURRENT) + len(ADDED) + 5
    long_one = "그리고 같은 방식으로 다른 조회 화면의 응답 시간도 함께 줄였습니다."
    payload = _payload(char_limit=limit)

    result = _append(AppendClient(Insertion(after_sentence_seq=2, sentences=[ADDED, long_one])), payload)

    assert result.draft.text == f"{FIRST}\n{SECOND}\n{ADDED}"
    assert _reasons(result) == []


def test_append_reverts_when_even_one_sentence_is_over_the_limit() -> None:
    """한 문장을 더해도 한도를 넘으면 앞 글로 되돌린다."""
    payload = _payload(char_limit=100)

    result = _append(AppendClient(Insertion(after_sentence_seq=2, sentences=[ADDED])), payload)

    assert result.draft.text == CURRENT
    assert _reasons(result) == ["더해도 글자 수를 넘겨 되돌렸다"]


def test_added_card_id_that_is_not_the_supporting_card_is_rejected_with_400() -> None:
    """added_card_id 가 보조 카드가 아니면 어느 카드를 더할지 몰라 요청을 받지 않는다(이 서버는 검증 실패를 400 INVALID_PAYLOAD 로 돌려준다)."""
    response = TestClient(app).post("/internal/cover-letter-append", json=_payload(added_card_id=7))

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_append_api_returns_the_new_draft(monkeypatch: pytest.MonkeyPatch) -> None:
    """덧붙이기 API 는 새 글과 단계를 공통 응답 형식으로 돌려준다."""
    client = AppendClient(Insertion(after_sentence_seq=2, sentences=[ADDED]))
    monkeypatch.setattr(cover_letters_api, "_appender", CoverLetterAppender(client, client))

    response = TestClient(app).post("/internal/cover-letter-append", json=_payload())

    assert response.status_code == 200
    assert response.json()["data"]["draft"]["text"] == f"{FIRST}\n{SECOND}\n{ADDED}"
