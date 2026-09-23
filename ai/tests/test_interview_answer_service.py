"""B-2 답변 충분성 × 남은 슬롯 × 질문 상한 판단표 테스트."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from main import app
from schemas.interview import InterviewAnswerRequest
from services.interview_answer_service import InterviewAnswerService


class StubEvaluator:
    """서비스 상태 조합 테스트에서 충분성 결과를 고정하는 판정기."""

    def __init__(self, sufficient: bool) -> None:
        self._sufficient = sufficient

    def is_sufficient(self, request: InterviewAnswerRequest) -> bool:
        return self._sufficient


def request_payload(
    *,
    with_other_slot: bool,
    existing_turn_count: int = 1,
    answer_source: str = "TYPED",
) -> dict:
    """B-2 서비스 판단표에서 공통으로 사용할 정상 요청 payload."""
    other_missing_slots = []
    if with_other_slot:
        other_missing_slots.append(
            {
                "star_slot": "T",
                "statement_seq": 1,
                "body": None,
                "confidence": "LOW",
                "linked_commits": [],
            }
        )

    return {
        "card_id": 101,
        "source_type": "PR",
        "candidate": {
            "github_pr_number": 222,
            "commits": [
                {
                    "commit_id": 790,
                    "sha": "abc1234",
                    "message": "세션 테이블 인덱스 추가",
                }
            ],
            "reviews": [],
            "issues": [],
        },
        "current_turn": {
            "target": {"star_slot": "R", "statement_seq": 1},
            "question_type": "EVIDENCE_GAP",
            "question_text": "인덱스를 추가한 뒤 실제 결과가 어땠나요?",
        },
        "answer_text": "응답 시간이 800ms에서 200ms로 줄었습니다.",
        "answer_source": answer_source,
        "other_missing_slots": other_missing_slots,
        "existing_turn_count": existing_turn_count,
    }


def service_with_result(sufficient: bool) -> InterviewAnswerService:
    """충분성 결과를 고정한 서비스 인스턴스를 만든다."""
    return InterviewAnswerService(evaluator=StubEvaluator(sufficient))


@pytest.mark.parametrize("existing_turn_count", [1, 2])
def test_sufficient_without_other_slot_completes(existing_turn_count: int) -> None:
    """답변이 충분하고 다른 슬롯이 없으면 질문 여유와 무관하게 종료한다."""
    request = InterviewAnswerRequest.model_validate(
        request_payload(
            with_other_slot=False,
            existing_turn_count=existing_turn_count,
        )
    )

    result = service_with_result(True).process_answer(501, request)

    assert result.outcome == "ANSWERED"
    assert result.resulting_statement is not None
    assert result.resulting_statement.body == request.answer_text
    assert result.remaining_slots == []
    assert result.next_action == "COMPLETE"
    assert result.next_turn is None


def test_sufficient_with_other_slot_and_turn_budget_asks_other_slot() -> None:
    """현재 답변이 충분하면 문장을 만들고 다음 부족 슬롯을 질문한다."""
    request = InterviewAnswerRequest.model_validate(
        request_payload(with_other_slot=True)
    )

    result = service_with_result(True).process_answer(501, request)

    assert result.outcome == "ANSWERED"
    assert result.resulting_statement is not None
    assert result.resulting_statement.star_slot == "R"
    assert result.remaining_slots[0].star_slot == "T"
    assert result.next_action == "ASK_AGAIN"
    assert result.next_turn is not None
    assert result.next_turn.target.star_slot == "T"
    assert result.next_turn.question_type == "RECALL_AID"


def test_sufficient_with_other_slot_at_turn_cap_completes() -> None:
    """현재 답변이 충분해도 상한이면 다른 부족 슬롯을 남기고 종료한다."""
    request = InterviewAnswerRequest.model_validate(
        request_payload(
            with_other_slot=True,
            existing_turn_count=2,
        )
    )

    result = service_with_result(True).process_answer(501, request)

    assert result.outcome == "ANSWERED"
    assert result.remaining_slots[0].star_slot == "T"
    assert result.next_action == "COMPLETE"
    assert result.next_turn is None


def test_insufficient_without_other_slot_and_turn_budget_reasks_target() -> None:
    """답변이 부족하면 현재 슬롯을 남기고 같은 슬롯을 FOLLOWUP한다."""
    request = InterviewAnswerRequest.model_validate(
        request_payload(with_other_slot=False)
    )

    result = service_with_result(False).process_answer(501, request)

    assert result.outcome == "INSUFFICIENT"
    assert result.resulting_statement is None
    assert [(slot.star_slot, slot.statement_seq) for slot in result.remaining_slots] == [
        ("R", 1)
    ]
    assert result.next_action == "ASK_AGAIN"
    assert result.next_turn is not None
    assert result.next_turn.target.star_slot == "R"
    assert result.next_turn.question_type == "FOLLOWUP"
    assert "PR #222" in result.next_turn.question_text


def test_insufficient_with_other_slot_and_turn_budget_preserves_all_slots() -> None:
    """현재 답변이 부족하면 현재 슬롯을 먼저 두고 다른 슬롯도 유지한다."""
    request = InterviewAnswerRequest.model_validate(
        request_payload(with_other_slot=True)
    )

    result = service_with_result(False).process_answer(501, request)

    assert [(slot.star_slot, slot.statement_seq) for slot in result.remaining_slots] == [
        ("R", 1),
        ("T", 1),
    ]
    assert result.next_turn is not None
    assert result.next_turn.target.star_slot == "R"


@pytest.mark.parametrize("with_other_slot", [False, True])
def test_insufficient_at_turn_cap_preserves_slots_and_completes(
    with_other_slot: bool,
) -> None:
    """답변이 부족해도 상한에 도달하면 남은 슬롯을 보존하고 종료한다."""
    request = InterviewAnswerRequest.model_validate(
        request_payload(
            with_other_slot=with_other_slot,
            existing_turn_count=2,
        )
    )

    result = service_with_result(False).process_answer(501, request)

    expected_slots = [("R", 1), ("T", 1)] if with_other_slot else [("R", 1)]
    assert [(slot.star_slot, slot.statement_seq) for slot in result.remaining_slots] == expected_slots
    assert result.next_action == "COMPLETE"
    assert result.next_turn is None


def test_selected_answer_maps_to_user_selected_evidence() -> None:
    """AI 선택지를 고른 답변은 USER_SELECTED 근거 유형으로 반환한다."""
    request = InterviewAnswerRequest.model_validate(
        request_payload(with_other_slot=False, answer_source="SELECTED")
    )

    result = service_with_result(True).process_answer(501, request)

    assert result.resulting_statement is not None
    assert result.resulting_statement.evidence_type == "USER_SELECTED"


def test_rule_evaluator_rejects_short_or_vague_typed_answer() -> None:
    """기본 판정기는 짧거나 회피적인 직접 입력 답변을 불충분하게 본다."""
    service = InterviewAnswerService()
    short_request = InterviewAnswerRequest.model_validate(
        {**request_payload(with_other_slot=False), "answer_text": "줄었어요"}
    )
    vague_request = InterviewAnswerRequest.model_validate(
        {
            **request_payload(with_other_slot=False),
            "answer_text": "구체적인 내용은 잘 모르겠습니다.",
        }
    )

    assert service.process_answer(501, short_request).outcome == "INSUFFICIENT"
    assert service.process_answer(501, vague_request).outcome == "INSUFFICIENT"


def test_rule_evaluator_accepts_concrete_typed_answer_without_rewriting() -> None:
    """구체적인 직접 입력 답변은 원문을 유지한 STAR 문장으로 만든다."""
    service = InterviewAnswerService()
    request = InterviewAnswerRequest.model_validate(
        request_payload(with_other_slot=False)
    )

    result = service.process_answer(501, request)

    assert result.outcome == "ANSWERED"
    assert result.resulting_statement is not None
    assert result.resulting_statement.body == request.answer_text
    assert result.resulting_statement.evidence_type == "USER_STATED"


def test_patch_endpoint_returns_common_envelope() -> None:
    """PATCH API가 B-2 결과를 공통 응답 봉투로 반환한다."""
    response = TestClient(app).patch(
        "/internal/interview-turns/501",
        json=request_payload(with_other_slot=False),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["turn_id"] == 501
    assert body["data"]["outcome"] == "ANSWERED"
    assert body["error"] is None


def test_patch_endpoint_rejects_non_positive_turn_id() -> None:
    """경로의 turn_id도 양수 DB ID 계약을 지킨다."""
    response = TestClient(app).patch(
        "/internal/interview-turns/0",
        json=request_payload(with_other_slot=False),
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"
