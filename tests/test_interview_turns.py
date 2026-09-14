"""`POST /internal/interview-turns` 단위 테스트 — B-1.

LLM/DB/GitHub API를 전혀 호출하지 않는 결정적 템플릿 로직을 검증한다.
명세서(gitory_api_spec_v2) 346행의 질문 생성 하드 규칙을 그대로 테스트로 옮긴다.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from main import app

client = TestClient(app)

ENDPOINT = "/internal/interview-turns"
ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."
NEGATIVE_ASSERTIONS = ("실패",)


def _post(payload: dict) -> dict:
    response = client.post(ENDPOINT, json=payload)
    return response


def test_revert_question_cites_sha() -> None:
    """revert 케이스는 질문 본문에 제공된 커밋 SHA를 반드시 인용한다."""
    response = _post(
        {
            "card_id": 101,
            "missing_slots": [
                {
                    "star_slot": "T",
                    "seq": 1,
                    "evidence_hint": {
                        "kind": "revert",
                        "sha": "b9c02d",
                        "note": "Revert 커밋 발견, 이유 없음",
                    },
                }
            ],
            "existing_turn_count": 0,
        }
    )
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    data = body["data"]
    assert data["question_type"] == "evidence_gap"
    assert data["next_action"] == "ask_again"
    assert "b9c02d" in data["question_text"]


def test_changes_requested_question_cites_pr_number() -> None:
    """changes_requested 케이스는 질문 본문에 제공된 PR 번호를 반드시 인용한다."""
    response = _post(
        {
            "card_id": 102,
            "missing_slots": [
                {
                    "star_slot": "A",
                    "seq": 1,
                    "evidence_hint": {"kind": "changes_requested", "pr_number": 222},
                }
            ],
            "existing_turn_count": 0,
        }
    )
    data = response.json()["data"]
    assert "222" in data["question_text"]


def test_issue_feedback_question_cites_pr_number() -> None:
    """issue_feedback 케이스도 질문 본문에 제공된 PR 번호를 반드시 인용한다."""
    response = _post(
        {
            "card_id": 103,
            "missing_slots": [
                {
                    "star_slot": "R",
                    "seq": 1,
                    "evidence_hint": {"kind": "issue_feedback", "pr_number": 77},
                }
            ],
            "existing_turn_count": 0,
        }
    )
    data = response.json()["data"]
    assert "77" in data["question_text"]


def test_all_generated_questions_include_escape_hatch() -> None:
    """모든 케이스(revert/changes_requested/issue_feedback/fallback)는 탈출구를 포함한다."""
    hints = [
        {"kind": "revert", "sha": "abc123"},
        {"kind": "changes_requested", "pr_number": 1},
        {"kind": "issue_feedback", "pr_number": 2},
        None,
    ]
    for hint in hints:
        response = _post(
            {
                "card_id": 200,
                "missing_slots": [{"star_slot": "S", "seq": 1, "evidence_hint": hint}],
                "existing_turn_count": 0,
            }
        )
        data = response.json()["data"]
        assert ESCAPE_HATCH in data["question_text"]


def test_no_negative_assertions_in_generated_questions() -> None:
    """"실패" 등 부정적 결과를 단정하는 표현이 없어야 한다."""
    hints = [
        {"kind": "revert", "sha": "abc123"},
        {"kind": "changes_requested", "pr_number": 1},
        {"kind": "issue_feedback", "pr_number": 2},
        None,
    ]
    for hint in hints:
        response = _post(
            {
                "card_id": 201,
                "missing_slots": [{"star_slot": "S", "seq": 1, "evidence_hint": hint}],
                "existing_turn_count": 0,
            }
        )
        data = response.json()["data"]
        for negative_word in NEGATIVE_ASSERTIONS:
            assert negative_word not in data["question_text"]


def test_existing_turn_count_cap_returns_complete() -> None:
    """existing_turn_count >= 2 면 질문을 생성하지 않고 next_action=complete."""
    response = _post(
        {
            "card_id": 300,
            "missing_slots": [
                {"star_slot": "T", "seq": 1, "evidence_hint": {"kind": "revert", "sha": "x"}}
            ],
            "existing_turn_count": 2,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["next_action"] == "complete"
    assert data["question_text"] is None
    assert data["star_slot"] is None


def test_no_missing_slots_returns_complete() -> None:
    """빈 칸이 없으면(missing_slots=[]) 상한 미만이어도 complete."""
    response = _post({"card_id": 301, "missing_slots": [], "existing_turn_count": 0})
    data = response.json()["data"]
    assert data["next_action"] == "complete"


def test_fallback_when_no_evidence() -> None:
    """증거 전무(evidence_hint=None)면 recall_aid로 fallback 질문을 생성한다."""
    response = _post(
        {
            "card_id": 400,
            "missing_slots": [{"star_slot": "R", "seq": 1, "evidence_hint": None}],
            "existing_turn_count": 0,
        }
    )
    data = response.json()["data"]
    assert data["question_type"] == "recall_aid"
    assert data["next_action"] == "ask_again"
    assert ESCAPE_HATCH in data["question_text"]


def test_invalid_payload_returns_envelope_error() -> None:
    """요청 검증 오류는 공통 에러 봉투(INVALID_PAYLOAD, HTTP 400)로 응답한다."""
    response = _post(
        {
            "card_id": 500,
            "missing_slots": [{"star_slot": "X", "seq": 1}],  # 잘못된 star_slot 값
            "existing_turn_count": 0,
        }
    )
    assert response.status_code == 400
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "INVALID_PAYLOAD"
    assert body["error"]["retryable"] is False


def test_missing_required_field_returns_invalid_payload() -> None:
    """필수 필드(card_id)가 없으면 마찬가지로 INVALID_PAYLOAD를 반환한다."""
    response = _post({"missing_slots": [], "existing_turn_count": 0})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"
