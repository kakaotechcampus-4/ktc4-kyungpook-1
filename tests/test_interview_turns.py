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
            "source_type": "commit_cluster",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    data = body["data"]
    assert data["question_type"] == "evidence_gap"
    assert data["next_action"] == "ask_again"
    assert "b9c02d" in data["question_text"]
    assert data["turn_seq"] == 1
    assert data["target_star_slot"] == "T"
    assert data["target_statement_seq"] == 1


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
            "source_type": "pr",
            "pr_number": 222,
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
            "source_type": "pr",
            "pr_number": 77,
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
                "source_type": "commit_cluster",
                "pr_number": None,
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
                "source_type": "commit_cluster",
                "pr_number": None,
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
            "source_type": "commit_cluster",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["next_action"] == "complete"
    assert data["question_text"] is None
    assert data["target_star_slot"] is None
    assert data["turn_seq"] is None
    assert data["target_statement_seq"] is None


def test_no_missing_slots_returns_complete() -> None:
    """빈 칸이 없으면(missing_slots=[]) 상한 미만이어도 complete."""
    response = _post(
        {
            "card_id": 301,
            "missing_slots": [],
            "existing_turn_count": 0,
            "source_type": "commit_cluster",
            "pr_number": None,
        }
    )
    data = response.json()["data"]
    assert data["next_action"] == "complete"


def test_fallback_when_no_evidence() -> None:
    """증거 전무(evidence_hint=None)면 recall_aid로 fallback 질문을 생성한다."""
    response = _post(
        {
            "card_id": 400,
            "missing_slots": [{"star_slot": "R", "seq": 1, "evidence_hint": None}],
            "existing_turn_count": 0,
            "source_type": "commit_cluster",
            "pr_number": None,
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
            "source_type": "commit_cluster",
            "pr_number": None,
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
    response = _post(
        {
            "missing_slots": [],
            "existing_turn_count": 0,
            "source_type": "commit_cluster",
            "pr_number": None,
        }
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_source_type_pr_without_pr_number_returns_invalid_payload() -> None:
    """source_type='pr'인데 pr_number가 없으면 INVALID_PAYLOAD를 반환한다."""
    response = _post(
        {
            "card_id": 600,
            "missing_slots": [],
            "existing_turn_count": 0,
            "source_type": "pr",
        }
    )
    assert response.status_code == 400
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "INVALID_PAYLOAD"


def test_source_type_pr_with_null_pr_number_returns_invalid_payload() -> None:
    """source_type='pr'인데 pr_number가 null이어도 INVALID_PAYLOAD를 반환한다."""
    response = _post(
        {
            "card_id": 601,
            "missing_slots": [],
            "existing_turn_count": 0,
            "source_type": "pr",
            "pr_number": None,
        }
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_source_type_pr_with_pr_number_succeeds() -> None:
    """source_type='pr'이고 pr_number가 있으면 정상 처리된다."""
    response = _post(
        {
            "card_id": 602,
            "missing_slots": [
                {
                    "star_slot": "A",
                    "seq": 1,
                    "evidence_hint": {"kind": "changes_requested", "pr_number": 55},
                }
            ],
            "existing_turn_count": 0,
            "source_type": "pr",
            "pr_number": 55,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["next_action"] == "ask_again"


def test_source_type_commit_cluster_allows_null_pr_number() -> None:
    """source_type='commit_cluster'면 pr_number=null이어도 정상 처리된다."""
    response = _post(
        {
            "card_id": 603,
            "missing_slots": [
                {"star_slot": "T", "seq": 1, "evidence_hint": {"kind": "revert", "sha": "aaa111"}}
            ],
            "existing_turn_count": 0,
            "source_type": "commit_cluster",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["next_action"] == "ask_again"


def test_source_type_direct_card_allows_null_pr_number() -> None:
    """source_type='direct_card'면 pr_number=null이어도 정상 처리된다."""
    response = _post(
        {
            "card_id": 604,
            "missing_slots": [
                {"star_slot": "S", "seq": 1, "evidence_hint": {"kind": "revert", "sha": "bbb222"}}
            ],
            "existing_turn_count": 0,
            "source_type": "direct_card",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["next_action"] == "ask_again"


def test_source_type_direct_card_without_evidence_hint_returns_recall_aid() -> None:
    """direct_card + evidence_hint 없음은 fallback/recall_aid 질문으로 정상 처리된다."""
    response = _post(
        {
            "card_id": 605,
            "missing_slots": [{"star_slot": "R", "seq": 1, "evidence_hint": None}],
            "existing_turn_count": 0,
            "source_type": "direct_card",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["question_type"] == "recall_aid"
    assert data["next_action"] == "ask_again"
    assert ESCAPE_HATCH in data["question_text"]


def test_source_type_direct_card_with_no_missing_slots_returns_complete() -> None:
    """direct_card이고 빈 칸이 없으면 상한 미만이어도 complete를 반환한다."""
    response = _post(
        {
            "card_id": 606,
            "missing_slots": [],
            "existing_turn_count": 0,
            "source_type": "direct_card",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["next_action"] == "complete"


def test_missing_source_type_returns_invalid_payload() -> None:
    """source_type 필드 자체가 없으면 INVALID_PAYLOAD를 반환한다."""
    response = _post({"card_id": 607, "missing_slots": [], "existing_turn_count": 0})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_turn_seq_is_existing_turn_count_plus_one() -> None:
    """turn_seq는 existing_turn_count + 1이다(카드별 되묻기 턴 순번)."""
    response = _post(
        {
            "card_id": 700,
            "missing_slots": [
                {"star_slot": "S", "seq": 3, "evidence_hint": {"kind": "revert", "sha": "ccc333"}}
            ],
            "existing_turn_count": 1,
            "source_type": "commit_cluster",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["turn_seq"] == 2
    assert data["target_star_slot"] == "S"
    assert data["target_statement_seq"] == 3


def test_target_statement_seq_independent_of_turn_seq() -> None:
    """target_statement_seq(STAR 문장 순번)는 turn_seq(되묻기 턴 순번)와 별개로 유지된다."""
    response = _post(
        {
            "card_id": 701,
            "missing_slots": [
                {"star_slot": "R", "seq": 4, "evidence_hint": {"kind": "issue_feedback", "pr_number": 9}}
            ],
            "existing_turn_count": 0,
            "source_type": "pr",
            "pr_number": 9,
        }
    )
    data = response.json()["data"]
    assert data["turn_seq"] == 1
    assert data["target_statement_seq"] == 4


def test_evidence_hint_accepts_optional_review_issue_comment_fields() -> None:
    """EvidenceHint의 review_id/issue_number/comment_excerpt는 선택 필드로 정상 수락된다."""
    response = _post(
        {
            "card_id": 702,
            "missing_slots": [
                {
                    "star_slot": "A",
                    "seq": 1,
                    "evidence_hint": {
                        "kind": "changes_requested",
                        "pr_number": 321,
                        "review_id": 9001,
                        "issue_number": 55,
                        "comment_excerpt": "이 부분 다시 봐주세요",
                    },
                }
            ],
            "existing_turn_count": 0,
            "source_type": "pr",
            "pr_number": 321,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert "321" in data["question_text"]
    assert data["question_type"] == "evidence_gap"


def test_evidence_hint_without_optional_fields_still_works() -> None:
    """review_id/issue_number/comment_excerpt를 생략해도 기존 동작이 유지된다."""
    response = _post(
        {
            "card_id": 703,
            "missing_slots": [
                {"star_slot": "T", "seq": 1, "evidence_hint": {"kind": "revert", "sha": "ddd444"}}
            ],
            "existing_turn_count": 0,
            "source_type": "commit_cluster",
            "pr_number": None,
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert "ddd444" in data["question_text"]
