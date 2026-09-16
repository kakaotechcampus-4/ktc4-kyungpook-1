"""커밋 중심 MVP의 `POST /internal/interview-turns` 단위 테스트."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from main import app

client = TestClient(app)

ENDPOINT = "/internal/interview-turns"
ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."


def _post(payload: dict):
    return client.post(ENDPOINT, json=payload)


def _commit(commit_id: int, sha: str, message: str) -> dict:
    return {"commit_id": commit_id, "sha": sha, "message": message}


def _candidate(*commits: dict, github_pr_number: int | None = None) -> dict:
    return {"github_pr_number": github_pr_number, "commits": list(commits)}


def _slot(**overrides: object) -> dict:
    slot = {
        "star_slot": "R",
        "statement_seq": 1,
        "body": None,
        "confidence": "LOW",
        "linked_commits": [],
    }
    slot.update(overrides)
    return slot


def test_revert_commit_in_candidate_creates_evidence_gap_question() -> None:
    """B는 외부 EvidenceHint 없이 카드 커밋 메시지에서 REVERT를 판단한다."""
    response = _post(
        {
            "card_id": 101,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(
                _commit(791, "b9c02d", 'Revert "세션 캐시 도입"'),
            ),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        }
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["question_type"] == "EVIDENCE_GAP"
    assert "b9c02d" in data["question_text"]
    assert data["target_star_slot"] == "R"
    assert data["target_statement_seq"] == 1
    assert "turn_seq" not in data


def test_linked_commit_creates_evidence_gap_question() -> None:
    """문장 직접 근거가 있으면 작업 맥락을 인용해 보강 질문을 만든다."""
    response = _post(
        {
            "card_id": 102,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(790, "abc123", "세션 테이블 인덱스 추가")),
            "missing_slots": [
                _slot(
                    star_slot="A",
                    linked_commits=[_commit(790, "abc123", "세션 테이블 인덱스 추가")],
                )
            ],
            "existing_turn_count": 0,
        }
    )

    data = response.json()["data"]
    assert data["question_type"] == "EVIDENCE_GAP"
    assert "세션 테이블 인덱스 추가" in data["question_text"]
    assert "abc123" in data["question_text"]


def test_empty_slot_uses_card_commit_as_recall_aid_context() -> None:
    """빈 R은 직접 근거가 없어도 카드 전체 커밋을 회상 질문의 힌트로 쓴다."""
    response = _post(
        {
            "card_id": 103,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(790, "abc123", "세션 테이블 인덱스 추가")),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        }
    )

    data = response.json()["data"]
    assert data["question_type"] == "RECALL_AID"
    assert "세션 테이블 인덱스 추가" in data["question_text"]
    assert "abc123" in data["question_text"]
    assert ESCAPE_HATCH in data["question_text"]


def test_pr_without_commits_cites_pr_number() -> None:
    """PR 커밋 문맥이 비어 있어도 제공된 PR 번호를 질문에 인용한다."""
    response = _post(
        {
            "card_id": 109,
            "source_type": "PR",
            "candidate": _candidate(github_pr_number=222),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        }
    )

    assert response.status_code == 200
    assert "PR #222" in response.json()["data"]["question_text"]


def test_direct_slot_commit_precedes_unrelated_candidate_revert() -> None:
    """슬롯의 직접 근거를 카드 전체의 무관한 revert보다 우선한다."""
    response = _post(
        {
            "card_id": 110,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(791, "dead111", 'Revert "다른 기능"')),
            "missing_slots": [
                _slot(
                    linked_commits=[_commit(790, "abc123", "세션 테이블 인덱스 추가")]
                )
            ],
            "existing_turn_count": 0,
        }
    )

    question_text = response.json()["data"]["question_text"]
    assert "abc123" in question_text
    assert "dead111" not in question_text


@pytest.mark.parametrize(
    "payload",
    [
        {
            "card_id": 117,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(791, "rev1234", 'Revert "캐시"')),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        },
        {
            "card_id": 118,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(),
            "missing_slots": [
                _slot(linked_commits=[_commit(790, "abc123", "인덱스 추가")])
            ],
            "existing_turn_count": 0,
        },
        {
            "card_id": 119,
            "source_type": "PR",
            "candidate": _candidate(github_pr_number=222),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        },
        {
            "card_id": 120,
            "source_type": "DIRECT_CARD",
            "candidate": None,
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        },
    ],
)
def test_every_question_has_escape_hatch_without_failure_assumption(payload: dict) -> None:
    """모든 질문 경로는 탈출구를 제공하고 실패를 단정하지 않는다."""
    response = _post(payload)

    question_text = response.json()["data"]["question_text"]
    assert ESCAPE_HATCH in question_text
    assert "실패" not in question_text


def test_direct_card_without_candidate_uses_general_recall_aid() -> None:
    """직접 작성 카드는 GitHub 문맥 없이 일반 회상 질문으로 처리한다."""
    response = _post(
        {
            "card_id": 104,
            "source_type": "DIRECT_CARD",
            "candidate": None,
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        }
    )

    data = response.json()["data"]
    assert data["question_type"] == "RECALL_AID"
    assert "당시 상황" in data["question_text"]


def test_external_evidence_hint_is_rejected() -> None:
    """EvidenceHint는 Spring 요청 DTO가 아니라 B 내부 판단 모델이다."""
    response = _post(
        {
            "card_id": 105,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(790, "abc123", "세션 테이블 인덱스 추가")),
            "missing_slots": [_slot(evidence_hint={"kind": "REVERT", "sha": "abc123"})],
            "existing_turn_count": 0,
        }
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


@pytest.mark.parametrize(
    "payload",
    [
        {
            "card_id": 111,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(),
            "missing_slots": [],
            "existing_turn_count": 0,
            "typo_field": "x",
        },
        {
            "card_id": 112,
            "source_type": "COMMIT_CLUSTER",
            "candidate": {**_candidate(), "typo_field": "x"},
            "missing_slots": [],
            "existing_turn_count": 0,
        },
        {
            "card_id": 113,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(
                {**_commit(790, "abc123", "인덱스 추가"), "typo_field": "x"}
            ),
            "missing_slots": [],
            "existing_turn_count": 0,
        },
    ],
)
def test_unknown_fields_are_rejected_at_every_request_level(payload: dict) -> None:
    """최상위·candidate·commit의 오타 필드는 조용히 무시하지 않는다."""
    response = _post(payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


@pytest.mark.parametrize(
    "payload",
    [
        {
            "card_id": 0,
            "source_type": "DIRECT_CARD",
            "candidate": None,
            "missing_slots": [],
            "existing_turn_count": 0,
        },
        {
            "card_id": 114,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(0, "abc123", "인덱스 추가")),
            "missing_slots": [],
            "existing_turn_count": 0,
        },
        {
            "card_id": 115,
            "source_type": "PR",
            "candidate": _candidate(github_pr_number=0),
            "missing_slots": [],
            "existing_turn_count": 0,
        },
        {
            "card_id": 116,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(790, "", "인덱스 추가")),
            "missing_slots": [],
            "existing_turn_count": 0,
        },
        {
            "card_id": 121,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(790, "abc123", "   ")),
            "missing_slots": [],
            "existing_turn_count": 0,
        },
        {
            "card_id": 122,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(),
            "missing_slots": [_slot(statement_seq=True)],
            "existing_turn_count": 0,
        },
        {
            "card_id": True,
            "source_type": "DIRECT_CARD",
            "candidate": None,
            "missing_slots": [],
            "existing_turn_count": 0,
        },
    ],
)
def test_invalid_identifiers_are_rejected(payload: dict) -> None:
    """DB 식별자와 질문에 인용할 근거 값은 유효해야 한다."""
    response = _post(payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_spring_owns_turn_sequence_and_final_limit() -> None:
    """AI는 순번을 반환하지 않고, 읽기용 턴 수가 상한이면 완료만 반환한다."""
    response = _post(
        {
            "card_id": 106,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(790, "abc123", "세션 테이블 인덱스 추가")),
            "missing_slots": [_slot()],
            "existing_turn_count": 2,
        }
    )

    data = response.json()["data"]
    assert data["next_action"] == "COMPLETE"
    assert data["question_text"] is None
    assert "turn_seq" not in data


def test_pr_candidate_requires_pr_number_and_uppercase_enum() -> None:
    """PR 출처는 PR 번호가 필요하고 소문자 enum은 거절한다."""
    missing_pr = _post(
        {
            "card_id": 107,
            "source_type": "PR",
            "candidate": _candidate(),
            "missing_slots": [],
            "existing_turn_count": 0,
        }
    )
    lowercase = _post(
        {
            "card_id": 108,
            "source_type": "commit_cluster",
            "candidate": _candidate(),
            "missing_slots": [],
            "existing_turn_count": 0,
        }
    )

    assert missing_pr.status_code == 400
    assert lowercase.status_code == 400
