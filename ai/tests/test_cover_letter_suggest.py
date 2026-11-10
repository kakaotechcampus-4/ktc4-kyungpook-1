"""자소서 재료 제안: 입력에 있는 카드·후보만 최대 3개 고르고, 규칙은 코드가 지키며, 맞는 것이 없으면 빈 목록이다."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest
from fastapi.testclient import TestClient

import api.cover_letters as cover_letters_api
from main import app
from schemas.cover_letter import SuggestionRequest
from services.cover_letter_suggester import CoverLetterSuggester, SuggestionOutput, SuggestOutput
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

ANGLE = "리뷰어와 의견이 갈렸다가 맞춘 과정을 의견 조율로 쓸 수 있어요"


class SuggestClient:
    """정해 둔 답을 차례로 돌려주고 받은 요청을 남긴다. 예외를 넣으면 그 차례에 던진다."""

    def __init__(self, *answers: Any) -> None:
        self._answers = list(answers)
        self.payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.payloads.append(dict(payload))
        answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _payload(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "cover_letter_id": 1,
        "job_role": "백엔드 개발자",
        "memo": None,
        "posting": None,
        "question": {"question_id": 1, "text": "의견이 달랐던 경험을 쓰세요.", "char_limit": 700, "question_theme": "CONFLICT"},
        "cards": [
            {"card_id": 12, "title": "캐시 방식 합의", "card_kind": "QUALITATIVE", "source_name": "me/shop",
             "period": None, "summary": "리뷰어와 캐시 방식을 두고 의견이 갈렸다가 맞췄다"},
            {"card_id": 13, "title": "배포 자동화", "card_kind": "TECH", "source_name": None,
             "period": "2025.03~2025.06", "summary": "배포 과정을 스크립트로 묶었다"},
        ],
        "candidates": [
            {"candidate_id": "c-1", "title": "리뷰 반영", "reason": "리뷰 댓글에 답하며 고침", "repo_name": "me/shop",
             "commit_messages": ["fix: 캐시 키 변경"], "review_comment_count": 9},
        ],
    }
    return body | overrides


def _item(source: str, id_: str, angle: str = ANGLE) -> SuggestionOutput:
    return SuggestionOutput(source=source, id=id_, angle=angle)


def _suggest(client: SuggestClient, payload: dict[str, Any] | None = None):
    request = SuggestionRequest.model_validate(payload or _payload())
    return asyncio.run(CoverLetterSuggester(client).suggest(request))


def _pairs(result) -> list[tuple[str, str]]:
    return [(item.source, item.id) for item in result.suggestions]


def test_a1_picks_only_ids_from_the_request_with_matching_source() -> None:
    """요청에 없는 id 와 다른 목록에서 온 id 는 받지 않고 한 번 더 묻는다."""
    wrong = SuggestOutput(suggestions=[_item("CARD", "99"), _item("CARD", "c-1")])
    good = SuggestOutput(suggestions=[_item("CARD", "12"), _item("CANDIDATE", "c-1")])
    client = SuggestClient(wrong, good)

    result = _suggest(client)

    assert _pairs(result) == [("CARD", "12"), ("CANDIDATE", "c-1")]
    assert len(client.payloads) == 2 and "previous_problem" in client.payloads[1]


def test_a2_retries_once_on_unknown_id_then_drops_it() -> None:
    """두 번 다 모르는 id 를 내면 그 항목만 버리고 맞는 항목은 돌려준다."""
    bad = SuggestOutput(suggestions=[_item("CARD", "12"), _item("CARD", "99")])
    client = SuggestClient(bad, bad)

    result = _suggest(client)

    assert _pairs(result) == [("CARD", "12")] and len(client.payloads) == 2


def test_a3_caps_at_three_and_removes_duplicates() -> None:
    """같은 재료를 두 번 고르거나 3개를 넘기면 처음 것만 두고 3개까지만 남긴다."""
    payload = _payload(candidates=[
        {"candidate_id": f"c-{n}", "title": "후보", "reason": "이유", "repo_name": "me/shop"} for n in range(1, 4)
    ])
    many = SuggestOutput(suggestions=[_item("CARD", "12"), _item("CARD", "12"), _item("CARD", "13"),
                                      _item("CANDIDATE", "c-1"), _item("CANDIDATE", "c-2")])
    client = SuggestClient(many, many)

    result = _suggest(client, payload)

    assert _pairs(result) == [("CARD", "12"), ("CARD", "13"), ("CANDIDATE", "c-1")]


def test_a4_empty_list_is_valid_without_retry() -> None:
    """맞는 것이 없다는 빈 목록은 정상 답이라 다시 묻지 않는다."""
    client = SuggestClient(SuggestOutput(suggestions=[]))

    result = _suggest(client)

    assert result.suggestions == [] and len(client.payloads) == 1


def test_a4b_nothing_to_choose_from_skips_the_llm() -> None:
    """고를 카드도 후보도 없으면 LLM 을 부르지 않는다."""
    client = SuggestClient()

    result = _suggest(client, _payload(cards=[], candidates=[]))

    assert result.suggestions == [] and client.payloads == []


def test_a5_angle_over_60_chars_is_retried_then_dropped() -> None:
    """angle 이 60자를 넘거나 비면 한 번 더 묻고, 그래도 어기면 그 항목을 버린다."""
    long_angle = "가" * 61
    bad = SuggestOutput(suggestions=[_item("CARD", "12", long_angle), _item("CARD", "13", "  "), _item("CANDIDATE", "c-1")])
    client = SuggestClient(bad, bad)

    result = _suggest(client)

    assert _pairs(result) == [("CANDIDATE", "c-1")] and len(client.payloads) == 2


def test_a6_llm_failure_returns_503_llm_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    """LLM 답을 못 받으면 빈 목록으로 대신하지 않고 503 LLM_UNAVAILABLE(retryable) 이다."""
    failure = LLMStructuredCallError("LLM 호출 실패")
    monkeypatch.setattr(cover_letters_api, "_suggester", CoverLetterSuggester(SuggestClient(failure, failure)))

    response = TestClient(app).post("/internal/cover-letter-suggestions", json=_payload())

    assert response.status_code == 503
    assert (response.json()["error"]["code"], response.json()["error"]["retryable"]) == ("LLM_UNAVAILABLE", True)


def test_a6b_api_returns_suggestions(monkeypatch: pytest.MonkeyPatch) -> None:
    """정상이면 200 과 suggestions 를 돌려준다."""
    answer = SuggestOutput(suggestions=[_item("CARD", "12")])
    monkeypatch.setattr(cover_letters_api, "_suggester", CoverLetterSuggester(SuggestClient(answer)))

    response = TestClient(app).post("/internal/cover-letter-suggestions", json=_payload())

    assert response.status_code == 200
    assert response.json()["data"]["suggestions"] == [{"source": "CARD", "id": "12", "angle": ANGLE}]


def test_a7_request_rejects_unknown_fields() -> None:
    """알 수 없는 필드는 조용히 버리지 않고 400 으로 거절한다."""
    response = TestClient(app).post("/internal/cover-letter-suggestions", json=_payload(extra_field=1))

    assert response.status_code == 400
