"""`POST /internal/interview-turns` 단위 테스트 — B-1.

LLM/DB/GitHub API를 호출하지 않는 결정적 템플릿 로직과 Spring ↔ AI
서버 계약을 검증한다.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from main import app

client = TestClient(app)

ENDPOINT = "/internal/interview-turns"
ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."
NEGATIVE_ASSERTIONS = ("실패",)


def _post(payload: dict):
    return client.post(ENDPOINT, json=payload)


def _candidate(github_pr_number: int | None = None) -> dict:
    """카드 전체 커밋 문맥이 있는 후보 요청 값을 만든다."""
    return {
        "github_pr_number": github_pr_number,
        "commits": [
            {
                "commit_id": 790,
                "sha": "abc123",
                "message": "세션 테이블 인덱스 추가",
            }
        ],
    }


def test_revert_question_cites_sha() -> None:
    """REVERT 케이스는 질문 본문에 제공된 커밋 SHA를 반드시 인용한다."""
    response = _post(
        {
            "card_id": 101,
            "candidate": _candidate(),
            "missing_slots": [
                {
                    "star_slot": "T",
                    "seq": 1,
                    "evidence_hint": {"kind": "REVERT", "sha": "b9c02d"},
                }
            ],
            "existing_turn_count": 0,
            "source_type": "COMMIT_CLUSTER",
        }
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["question_type"] == "EVIDENCE_GAP"
    assert data["next_action"] == "ASK_AGAIN"
    assert "b9c02d" in data["question_text"]
    assert "turn_seq" not in data
    assert data["target_star_slot"] == "T"
    assert data["target_statement_seq"] == 1


def test_preclassified_pr_cases_use_uppercase_enums() -> None:
    """PR 리뷰/이슈 정황은 대문자 enum으로 전달하고 질문에 PR 번호를 인용한다."""
    for kind in ("CHANGES_REQUESTED", "ISSUE_FEEDBACK"):
        response = _post(
            {
                "card_id": 102,
                "candidate": _candidate(222),
                "missing_slots": [
                    {
                        "star_slot": "A",
                        "seq": 1,
                        "evidence_hint": {"kind": kind, "pr_number": 222},
                    }
                ],
                "existing_turn_count": 0,
                "source_type": "PR",
            }
        )
        data = response.json()["data"]
        assert data["question_type"] == "EVIDENCE_GAP"
        assert "222" in data["question_text"]


def test_card_commit_context_guides_question_when_slot_has_no_direct_commit() -> None:
    """비어 있는 R은 직접 근거가 없어도 카드 전체 커밋을 질문 힌트로 사용한다."""
    response = _post(
        {
            "card_id": 400,
            "candidate": _candidate(),
            "missing_slots": [
                {
                    "star_slot": "R",
                    "seq": 1,
                    "body": None,
                    "confidence": "LOW",
                    "linked_commits": [],
                }
            ],
            "existing_turn_count": 0,
            "source_type": "COMMIT_CLUSTER",
        }
    )
    data = response.json()["data"]
    assert data["question_type"] == "RECALL_AID"
    assert "세션 테이블 인덱스 추가" in data["question_text"]
    assert ESCAPE_HATCH in data["question_text"]


def test_direct_card_without_candidate_uses_general_recall_aid() -> None:
    """직접 작성 카드에는 후보/커밋 문맥 없이 일반 회상 질문을 생성한다."""
    response = _post(
        {
            "card_id": 401,
            "candidate": None,
            "missing_slots": [{"star_slot": "R", "seq": 1, "linked_commits": []}],
            "existing_turn_count": 0,
            "source_type": "DIRECT_CARD",
        }
    )
    data = response.json()["data"]
    assert data["question_type"] == "RECALL_AID"
    assert "당시 상황" in data["question_text"]


def test_all_generated_questions_include_escape_hatch_and_no_negative_assertion() -> None:
    """모든 생성 질문은 탈출구를 포함하고 부정적 결과를 단정하지 않는다."""
    hints = [
        {"kind": "REVERT", "sha": "abc123"},
        {"kind": "CHANGES_REQUESTED", "pr_number": 1},
        {"kind": "ISSUE_FEEDBACK", "pr_number": 2},
        None,
    ]
    for hint in hints:
        response = _post(
            {
                "card_id": 200,
                "candidate": _candidate(),
                "missing_slots": [{"star_slot": "S", "seq": 1, "evidence_hint": hint}],
                "existing_turn_count": 0,
                "source_type": "COMMIT_CLUSTER",
            }
        )
        question = response.json()["data"]["question_text"]
        assert ESCAPE_HATCH in question
        assert all(word not in question for word in NEGATIVE_ASSERTIONS)


def test_existing_turn_count_cap_returns_complete_without_turn_sequence() -> None:
    """AI는 완료 여부만 반환하고 DB 저장 순번은 반환하지 않는다."""
    response = _post(
        {
            "card_id": 300,
            "candidate": _candidate(),
            "missing_slots": [{"star_slot": "T", "seq": 1}],
            "existing_turn_count": 2,
            "source_type": "COMMIT_CLUSTER",
        }
    )
    data = response.json()["data"]
    assert data["next_action"] == "COMPLETE"
    assert data["question_text"] is None
    assert data["target_star_slot"] is None
    assert "turn_seq" not in data


def test_pr_candidate_requires_github_pr_number() -> None:
    """PR 후보는 반드시 GitHub PR 번호를 포함해야 한다."""
    response = _post(
        {
            "card_id": 600,
            "candidate": {"commits": []},
            "missing_slots": [],
            "existing_turn_count": 0,
            "source_type": "PR",
        }
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_lowercase_enum_returns_invalid_payload() -> None:
    """DB/FE 계약과 다른 소문자 enum은 요청 검증에서 거절한다."""
    response = _post(
        {
            "card_id": 601,
            "candidate": _candidate(),
            "missing_slots": [],
            "existing_turn_count": 0,
            "source_type": "commit_cluster",
        }
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"
