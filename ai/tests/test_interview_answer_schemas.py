"""B-2 인터뷰 답변 요청·응답 스키마 계약 테스트."""

import pytest
from pydantic import ValidationError

from schemas.interview import InterviewAnswerRequest, InterviewAnswerResult


def valid_request() -> dict:
    """테스트에서 공통으로 사용할 정상적인 Spring → AI 요청."""
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
            "target": {
                "star_slot": "R",
                "statement_seq": 1,
            },
            "question_type": "EVIDENCE_GAP",
            "question_text": "인덱스를 추가한 뒤 실제 결과가 어땠나요?",
        },
        "answer_text": "응답 시간이 800ms에서 200ms로 줄었습니다.",
        "answer_source": "TYPED",
        "other_missing_slots": [
            {
                "star_slot": "T",
                "statement_seq": 1,
                "body": None,
                "confidence": "LOW",
                "linked_commits": [],
            }
        ],
        "existing_turn_count": 1,
    }


def valid_answered_result() -> dict:
    """충분한 답변을 반영하고 인터뷰를 종료하는 정상 응답."""
    return {
        "turn_id": 501,
        "outcome": "ANSWERED",
        "resulting_statement": {
            "star_slot": "R",
            "statement_seq": 1,
            "body": "응답 시간이 800ms에서 200ms로 줄었습니다.",
            "evidence_type": "USER_STATED",
            "confidence": "HIGH",
            "source_turn_id": 501,
        },
        "remaining_slots": [],
        "next_action": "COMPLETE",
        "next_turn": None,
    }


def valid_insufficient_result() -> dict:
    """답변이 부족해 현재 R 슬롯을 다시 질문하는 정상 응답."""
    return {
        "turn_id": 501,
        "outcome": "INSUFFICIENT",
        "resulting_statement": None,
        "remaining_slots": [
            {
                "star_slot": "R",
                "statement_seq": 1,
            }
        ],
        "next_action": "ASK_AGAIN",
        "next_turn": {
            "target": {
                "star_slot": "R",
                "statement_seq": 1,
            },
            "question_type": "FOLLOWUP",
            "trigger_source": "AUTO",
            "question_text": "정확한 수치가 아니어도 괜찮아요. 어떤 변화가 있었나요?",
        },
    }


# 1. 정상 입력


def test_accepts_valid_answer_request() -> None:
    """정상적인 PR 기반 B-2 요청을 허용한다."""
    request = InterviewAnswerRequest.model_validate(valid_request())

    assert request.card_id == 101
    assert request.source_type == "PR"
    assert request.current_turn.target.star_slot == "R"
    assert request.current_turn.target.statement_seq == 1
    assert request.answer_source == "TYPED"
    assert request.other_missing_slots[0].star_slot == "T"


def test_accepts_direct_card_without_candidate() -> None:
    """직접 작성 카드는 GitHub 후보 문맥 없이도 허용한다."""
    payload = valid_request()
    payload["source_type"] = "DIRECT_CARD"
    payload["candidate"] = None

    request = InterviewAnswerRequest.model_validate(payload)

    assert request.source_type == "DIRECT_CARD"
    assert request.candidate is None


def test_accepts_turn_count_at_fixed_limit() -> None:
    """이미 생성된 질문 수가 확정 상한 2와 같아도 요청 자체는 유효하다."""
    payload = valid_request()
    payload["existing_turn_count"] = 2

    request = InterviewAnswerRequest.model_validate(payload)

    assert request.existing_turn_count == 2


# 2. 각 enum의 잘못된 값


@pytest.mark.parametrize(
    ("field", "invalid_value"),
    [
        ("source_type", "pr"),
        ("answer_source", "WRITTEN"),
    ],
)
def test_rejects_invalid_top_level_enum(field: str, invalid_value: str) -> None:
    """대문자 계약에 없거나 정의되지 않은 최상위 enum 값을 거절한다."""
    payload = valid_request()
    payload[field] = invalid_value

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_invalid_question_type() -> None:
    """정의되지 않은 질문 유형을 거절한다."""
    payload = valid_request()
    payload["current_turn"]["question_type"] = "CLARIFICATION"

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_invalid_star_slot() -> None:
    """S/T/A/R 이외의 STAR 슬롯을 거절한다."""
    payload = valid_request()
    payload["current_turn"]["target"]["star_slot"] = "X"

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


# 3. 각 숫자의 경계값


def test_rejects_zero_card_id() -> None:
    """DB ID는 0보다 커야 한다."""
    payload = valid_request()
    payload["card_id"] = 0

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_zero_statement_seq() -> None:
    """STAR 문장 순번은 1부터 시작한다."""
    payload = valid_request()
    payload["current_turn"]["target"]["statement_seq"] = 0

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_zero_turn_count() -> None:
    """B-2의 현재 질문 수는 1 이상이어야 한다."""
    payload = valid_request()
    payload["existing_turn_count"] = 0

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_turn_count_above_fixed_limit() -> None:
    """이미 생성된 질문 수가 확정 상한 2보다 크면 거절한다."""
    payload = valid_request()
    payload["existing_turn_count"] = 3

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_external_max_turns_field() -> None:
    """백엔드 소유 정책인 max_turns는 B-2 요청에 포함하지 않는다."""
    payload = valid_request()
    payload["max_turns"] = 2

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


# 4. Optional이 null이면 안 되는 조합


def test_rejects_pr_without_candidate() -> None:
    """PR 출처는 PR 번호를 담은 candidate가 필요하다."""
    payload = valid_request()
    payload["candidate"] = None

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_pr_without_pr_number() -> None:
    """PR 출처의 candidate에는 github_pr_number가 필요하다."""
    payload = valid_request()
    payload["candidate"]["github_pr_number"] = None

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_commit_cluster_without_candidate() -> None:
    """커밋 묶음 출처는 최소한 빈 CandidateContext라도 필요하다."""
    payload = valid_request()
    payload["source_type"] = "COMMIT_CLUSTER"
    payload["candidate"] = None

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


# 5. A이면 B 관계의 반대 사례


def test_rejects_direct_card_with_candidate() -> None:
    """직접 작성 카드에는 GitHub candidate 문맥이 존재하면 안 된다."""
    payload = valid_request()
    payload["source_type"] = "DIRECT_CARD"

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


@pytest.mark.parametrize("missing_field", ["card_id", "current_turn", "answer_text"])
def test_rejects_missing_required_field(missing_field: str) -> None:
    """핵심 필수 필드가 빠진 요청을 거절한다."""
    payload = valid_request()
    del payload[missing_field]

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_blank_answer() -> None:
    """공백만 있는 답변을 실제 사용자 답변으로 허용하지 않는다."""
    payload = valid_request()
    payload["answer_text"] = "   "

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


# 6. 리스트 중복


def test_rejects_current_target_in_other_missing_slots() -> None:
    """현재 질문 대상이 다른 부족한 슬롯 목록에 중복되는 것을 거절한다."""
    payload = valid_request()
    payload["other_missing_slots"] = [
        {
            "star_slot": "R",
            "statement_seq": 1,
            "body": None,
            "confidence": "LOW",
            "linked_commits": [],
        }
    ]

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_duplicate_other_missing_slots() -> None:
    """같은 다른 부족한 슬롯이 두 번 들어오는 것을 거절한다."""
    duplicated_slot = {
        "star_slot": "T",
        "statement_seq": 1,
        "body": None,
        "confidence": "LOW",
        "linked_commits": [],
    }
    payload = valid_request()
    payload["other_missing_slots"] = [duplicated_slot, duplicated_slot.copy()]

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


# 7. 실제로 발생했던 계약 버그의 회귀 테스트


def test_rejects_unknown_top_level_field() -> None:
    """정의되지 않은 최상위 요청 필드를 조용히 무시하지 않는다."""
    payload = valid_request()
    payload["typo_field"] = "ignored in the old contract"

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_unknown_nested_field() -> None:
    """중첩된 current_turn의 정의되지 않은 필드도 거절한다."""
    payload = valid_request()
    payload["current_turn"]["typo_field"] = "unexpected"

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


def test_rejects_camel_case_alias_instead_of_snake_case() -> None:
    """Spring이 잘못 보낸 camelCase 필드가 조용히 무시되는 회귀를 막는다."""
    payload = valid_request()
    payload["existingTurnCount"] = payload.pop("existing_turn_count")

    with pytest.raises(ValidationError):
        InterviewAnswerRequest.model_validate(payload)


# 응답 DTO의 조건부 필드 계약


def test_accepts_answered_result() -> None:
    """충분한 답변은 resulting_statement와 함께 반환할 수 있다."""
    result = InterviewAnswerResult.model_validate(valid_answered_result())

    assert result.outcome == "ANSWERED"
    assert result.resulting_statement is not None
    assert result.next_action == "COMPLETE"


def test_accepts_insufficient_result_with_followup() -> None:
    """불충분한 답변은 현재 슬롯과 FOLLOWUP 질문을 반환할 수 있다."""
    result = InterviewAnswerResult.model_validate(valid_insufficient_result())

    assert result.outcome == "INSUFFICIENT"
    assert result.resulting_statement is None
    assert result.remaining_slots[0].star_slot == "R"
    assert result.next_turn is not None
    assert result.next_turn.question_type == "FOLLOWUP"


def test_accepts_answered_result_with_another_slot_question() -> None:
    """현재 답변이 충분해도 다른 슬롯이 남으면 다음 질문을 허용한다."""
    payload = valid_answered_result()
    payload["remaining_slots"] = [
        {
            "star_slot": "T",
            "statement_seq": 1,
        }
    ]
    payload["next_action"] = "ASK_AGAIN"
    payload["next_turn"] = {
        "target": {
            "star_slot": "T",
            "statement_seq": 1,
        },
        "question_type": "EVIDENCE_GAP",
        "trigger_source": "AUTO",
        "question_text": "이 작업에서 해결해야 했던 과제는 무엇이었나요?",
    }

    result = InterviewAnswerResult.model_validate(payload)

    assert result.outcome == "ANSWERED"
    assert result.next_action == "ASK_AGAIN"
    assert result.next_turn is not None
    assert result.next_turn.target.star_slot == "T"


def test_rejects_answered_without_resulting_statement() -> None:
    """ANSWERED인데 생성된 STAR 문장이 없는 모순을 거절한다."""
    payload = valid_answered_result()
    payload["resulting_statement"] = None

    with pytest.raises(ValidationError):
        InterviewAnswerResult.model_validate(payload)


def test_rejects_insufficient_with_resulting_statement() -> None:
    """INSUFFICIENT인데 STAR 문장을 생성한 모순을 거절한다."""
    payload = valid_insufficient_result()
    payload["resulting_statement"] = valid_answered_result()[
        "resulting_statement"
    ]

    with pytest.raises(ValidationError):
        InterviewAnswerResult.model_validate(payload)


def test_rejects_ask_again_without_next_turn() -> None:
    """ASK_AGAIN이면 실제 다음 질문이 반드시 필요하다."""
    payload = valid_insufficient_result()
    payload["next_turn"] = None

    with pytest.raises(ValidationError):
        InterviewAnswerResult.model_validate(payload)


def test_rejects_ask_again_without_remaining_slots() -> None:
    """ASK_AGAIN인데 질문할 남은 슬롯이 없는 모순을 거절한다."""
    payload = valid_insufficient_result()
    payload["remaining_slots"] = []

    with pytest.raises(ValidationError):
        InterviewAnswerResult.model_validate(payload)


def test_rejects_next_target_outside_remaining_slots() -> None:
    """다음 질문 대상은 남아 있는 슬롯 중 하나여야 한다."""
    payload = valid_insufficient_result()
    payload["next_turn"]["target"] = {
        "star_slot": "T",
        "statement_seq": 1,
    }

    with pytest.raises(ValidationError):
        InterviewAnswerResult.model_validate(payload)


def test_rejects_complete_with_next_turn() -> None:
    """COMPLETE 응답에는 다음 질문이 존재하면 안 된다."""
    payload = valid_answered_result()
    payload["remaining_slots"] = [
        {
            "star_slot": "T",
            "statement_seq": 1,
        }
    ]
    payload["next_turn"] = valid_insufficient_result()["next_turn"]

    with pytest.raises(ValidationError):
        InterviewAnswerResult.model_validate(payload)


def test_allows_complete_with_unresolved_slots_at_turn_cap() -> None:
    """질문 상한 종료라면 부족한 슬롯을 남긴 COMPLETE 응답도 허용한다."""
    payload = valid_answered_result()
    payload["remaining_slots"] = [
        {
            "star_slot": "T",
            "statement_seq": 1,
        }
    ]

    result = InterviewAnswerResult.model_validate(payload)

    assert result.next_action == "COMPLETE"
    assert result.remaining_slots[0].star_slot == "T"
