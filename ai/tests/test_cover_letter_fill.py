"""채팅에서 받은 답으로 다시 쓰기: 빈칸 자리만 쓰고, 새 문제가 생기거나 다른 문장을 여럿 바꾸면 앞 글로 되돌린다."""

from __future__ import annotations

import asyncio
from typing import Any, Optional

import pytest
from fastapi.testclient import TestClient

import api.cover_letters as cover_letters_api
from main import app
from schemas.cover_letter import FillRequest
from services.cover_letter_drafter import FindingOutput, ReviewOutput
from services.cover_letter_fill import FIX_ONLY_NEW, CoverLetterFiller
from services.cover_letter_writer import WriteOutput
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

QID = 4
BLANK = "[더 쓸 거리: 꾸준히 익힌 행동]"
FIRST = "주문 조회가 느리다는 지적을 받고 백엔드 역량이 부족하다고 느꼈습니다."
THIRD = "그 뒤 주문 목록 조회를 한 번에 묶어 가져오게 바꿨습니다."
CURRENT = f"{FIRST}\n{BLANK}\n{THIRD}"
ANSWER = "매주 조회 성능 글을 하나씩 읽고 작은 예제로 따라 해 봤다."
NEW = "매주 조회 성능 글을 하나씩 읽고 작은 예제로 따라 해 보았습니다."
FILLED = f"{FIRST}\n{NEW}\n{THIRD}"
CARD = {
    "card_id": 7, "title": "주문 조회 성능 개선", "source_name": "shop",
    "statements": [
        {"star_slot": "S", "text": "주문 조회가 느리다는 지적을 받았다"},
        {"star_slot": "A", "text": "주문 목록 조회를 한 번에 묶어 가져오게 바꿨다"},
    ],
}


class FillClient:
    """쓰기·감독 답을 종류별로 차례로 돌려주고 받은 요청을 남긴다. 감독 답을 정하지 않으면 걸린 문장이 없다."""

    def __init__(self, writes: list[Any], reviews: Optional[list[Any]] = None) -> None:
        self._answers: dict[type, list[Any]] = {WriteOutput: list(writes), ReviewOutput: list(reviews or [])}
        self.payloads: dict[type, list[dict[str, Any]]] = {WriteOutput: [], ReviewOutput: []}

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.payloads[output_model].append(payload)
        queue = self._answers[output_model]
        answer = queue.pop(0) if queue or output_model is not ReviewOutput else ReviewOutput(findings=[])
        if isinstance(answer, Exception):
            raise answer
        if isinstance(answer, str):
            answer = WriteOutput(text=answer)
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)

    @property
    def writes(self) -> list[dict[str, Any]]:
        return self.payloads[WriteOutput]


def _payload(current: str = CURRENT, *, blank: Optional[str] = BLANK, inferred: tuple = (), **extra: Any) -> dict:
    return {
        "cover_letter_id": 12, "job_role": "백엔드 개발", "memo": None, "posting": None, "others": [],
        "target": {"question_id": QID, "text": "직무 역량을 키우기 위해 노력한 경험을 적어 주세요", "char_limit": 700,
                   "question_theme": "GROWTH", "primary_card": CARD,
                   "answers": [{"item": "ACTION", "text": ANSWER, "card_id": 7}],
                   "current_text": current, "inferred_seqs": list(inferred)},
        "filled_item": "ACTION", "blank": blank, **extra,
    }


def _fill(client: FillClient, payload: Optional[dict] = None):
    return asyncio.run(CoverLetterFiller(client, client).fill(FillRequest.model_validate(payload or _payload())))


def test_request_with_unknown_field_is_rejected_with_400() -> None:
    """계약에 없는 칸을 보내면 조용히 버리지 않고 400 INVALID_PAYLOAD 다."""
    response = TestClient(app).post("/internal/cover-letter-fill", json=_payload(extra_field=1))

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_filled_item_without_its_answer_is_rejected_with_400() -> None:
    """target.answers 에 filled_item 의 답이 없으면 무엇으로 쓸지 몰라 400 이다."""
    response = TestClient(app).post("/internal/cover-letter-fill", json=_payload(filled_item="RESULT"))

    assert response.status_code == 400


def test_fill_api_returns_rewritten_draft(monkeypatch: pytest.MonkeyPatch) -> None:
    """답으로 다시 쓰기 API 는 다시 쓴 글과 단계를 공통 응답 형식으로 돌려준다."""
    client = FillClient([FILLED])
    monkeypatch.setattr(cover_letters_api, "_filler", CoverLetterFiller(client, client))

    response = TestClient(app).post("/internal/cover-letter-fill", json=_payload())

    body = response.json()
    assert response.status_code == 200
    assert (body["data"]["draft"]["text"], body["meta"]["ended_by"]) == (FILLED, "COMPLETED")


def test_llm_failure_returns_503_llm_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    """쓰기 답을 못 받으면 지금 글을 다시 쓴 글로 내지 않고 503 LLM_UNAVAILABLE(retryable) 이다."""
    failure = LLMStructuredCallError("LLM 호출 실패")
    client = FillClient([failure, failure])
    monkeypatch.setattr(cover_letters_api, "_filler", CoverLetterFiller(client, client))

    response = TestClient(app).post("/internal/cover-letter-fill", json=_payload())

    assert response.status_code == 503
    assert (response.json()["error"]["code"], response.json()["error"]["retryable"]) == ("LLM_UNAVAILABLE", True)


def test_fill_asks_to_write_only_the_blank_spot() -> None:
    """빈칸 안의 글만 받아도 글 속 빈칸을 찾아, 그 자리만 새 답으로 쓰고 나머지는 그대로 두라고 지금 글과 함께 넘긴다."""
    client = FillClient([FILLED])

    result = _fill(client, _payload(blank="꾸준히 익힌 행동"))

    fix = client.writes[0]["fix"]
    assert fix["previous_draft"] == CURRENT
    assert fix["problems"] == [f"'{BLANK}' 자리를 새 답(행동: '{ANSWER}')으로 쓰고 나머지 문장은 그대로 두세요."]
    assert [s.text for s in result.draft.sentences] == [FIRST, NEW, THIRD]


def test_without_a_blank_the_answer_is_added_as_one_sentence() -> None:
    """채울 빈칸이 없으면 그 내용이 들어갈 자리에 한 문장 이하로 더하라고 넘긴다."""
    current = f"{FIRST}\n{THIRD}"
    client = FillClient([FILLED])

    _fill(client, _payload(current, blank=None))

    assert client.writes[0]["fix"]["problems"] == [
        f"새 답(행동: '{ANSWER}')을 그 내용이 들어갈 자리에 한 문장 이하로 더하고 나머지 문장은 그대로 두세요."]


def test_item_blank_of_the_filled_item_is_written_and_not_demanded_back() -> None:
    """빈칸 글이 없어도 그 항목의 빈칸([행동: …])을 찾아 쓰고, 다시 쓴 글에서 그 빈칸이 빠진 것을 문제로 보지 않는다."""
    item_blank = "[행동: 직접 한 일을 적어 주세요]"
    client = FillClient([FILLED])

    result = _fill(client, _payload(f"{FIRST}\n{item_blank}\n{THIRD}", blank=None))

    assert client.writes[0]["fix"]["problems"][0].startswith(f"'{item_blank}' 자리를")
    assert (result.draft.text, result.draft.blanks) == (FILLED, [])


def test_rewrite_that_changes_other_sentences_is_reverted() -> None:
    """빈칸 자리 밖의 문장을 둘 이상 바꾸면 앞 글로 되돌리고 REVERTED 를 남긴다."""
    changed = f"주문 조회가 느려 역량을 키우기로 했습니다.\n{NEW}\n그 뒤 조회 방식을 고쳤습니다."
    client = FillClient([changed])

    result = _fill(client)

    assert result.draft.text == CURRENT
    assert "REVERTED" in [step.kind for step in result.steps]
    assert client.payloads[ReviewOutput] == []


def test_new_problem_is_fixed_once_before_keeping_the_rewrite() -> None:
    """다시 쓴 글이 앞 글에 없던 문제(근거 없는 숫자)로 걸리면 새로 쓴 문장 안에서만 그 문제를 한 번 더 고쳐 쓰게 하고, 고쳐지면 그 글을 쓴다."""
    client = FillClient([f"{FIRST}\n3개월 동안 {NEW}\n{THIRD}", FILLED])

    result = _fill(client)

    assert client.writes[1]["fix"]["problems"] == ["'3개월' 는 입력에 없는 숫자입니다. 빼거나 입력에 있는 숫자로 바꾸세요.", FIX_ONLY_NEW]
    assert (result.draft.text, "REVERTED" in [step.kind for step in result.steps]) == (FILLED, False)


def test_new_problem_left_after_the_fix_is_reverted() -> None:
    """한 번 더 고쳐 써도 새 문제가 남으면 앞 글로 되돌리고 REVERTED 를 남긴다."""
    invented = f"{FIRST}\n3개월 동안 {NEW}\n{THIRD}"
    client = FillClient([invented, invented])

    result = _fill(client)

    assert (len(client.writes), result.draft.text) == (2, CURRENT)
    assert "REVERTED" in [step.kind for step in result.steps]


def test_unchanged_sentences_keep_their_inferred_status() -> None:
    """그대로 남은 문장은 짐작 표시 여부를 그대로 둔다: 확인 안 한 짐작은 남기고, 확인한 문장은 쓰기 AI 가 짐작이라 해도 달지 않는다."""
    guess = "그 일로 꾸준히 익히는 습관이 중요하다고 느꼈습니다."
    confirmed = "작은 개선도 팀에 도움이 된다고 생각했습니다."
    current = f"{CURRENT}\n{guess}\n{confirmed}"
    client = FillClient([WriteOutput(text=f"{FILLED}\n{guess}\n{confirmed}", inferred=[confirmed])])

    result = _fill(client, _payload(current, inferred=(4,)))

    assert [(m.code, m.sentence_seq) for m in result.draft.marks] == [("INFERRED", 4)]
    assert client.writes[0]["fix"]["previous_inferred"] == [guess]


def test_supervisor_findings_only_on_new_sentences_are_sent_back() -> None:
    """감독이 건 문장 가운데 새로 쓴 문장만 돌려보내고, 그대로 둔 문장은 다시 고치지 않는다."""
    review = ReviewOutput(findings=[
        FindingOutput(question_id=QID, sentence_seq=1, code="UNSUPPORTED_CLAIM", reason="근거에 없는 판단"),
        FindingOutput(question_id=QID, sentence_seq=2, code="UNSUPPORTED_CLAIM", reason="근거에 없는 횟수"),
    ])
    client = FillClient([FILLED, FILLED], reviews=[review])

    result = _fill(client)

    assert len(client.writes) == 2
    assert [problem[:6] for problem in client.writes[1]["fix"]["problems"]] == ["2번 문장에"]
    assert [(m.code, m.sentence_seq) for m in result.draft.marks] == [("UNSUPPORTED_CLAIM", 2)]
