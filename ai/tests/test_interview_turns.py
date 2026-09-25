"""근거 기반 `POST /internal/interview-turns` 단위 테스트."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from main import app

client = TestClient(app)

ENDPOINT = "/internal/interview-turns"
ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."


def _post(payload: dict):
    return client.post(ENDPOINT, json={"max_turns": 2, **payload})


def _commit(commit_id: int, sha: str, message: str) -> dict:
    return {"commit_id": commit_id, "sha": sha, "message": message}


def _review(
    review_id: int,
    pr_number: int,
    summary: str,
    state: str = "CHANGES_REQUESTED",
) -> dict:
    return {
        "review_id": review_id,
        "pr_number": pr_number,
        "state": state,
        "summary": summary,
    }


def _issue(issue_number: int, title: str, summary: str | None = None) -> dict:
    return {"issue_number": issue_number, "title": title, "summary": summary}


def _candidate(
    *commits: dict,
    github_pr_number: int | None = None,
    reviews: list[dict] | None = None,
    issues: list[dict] | None = None,
) -> dict:
    return {
        "github_pr_number": github_pr_number,
        "commits": list(commits),
        "reviews": reviews or [],
        "issues": issues or [],
    }


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


def test_changes_requested_review_creates_evidence_gap_question() -> None:
    """변경 요청 리뷰가 전달되면 PR·리뷰 식별자와 요약을 인용한다."""
    response = _post(
        {
            "card_id": 125,
            "source_type": "PR",
            "candidate": _candidate(
                github_pr_number=222,
                reviews=[
                    _review(
                        9001,
                        222,
                        "서비스와 저장소의 책임을 분리해 주세요.",
                    )
                ],
            ),
            "missing_slots": [_slot(star_slot="A")],
            "existing_turn_count": 0,
        }
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["question_type"] == "EVIDENCE_GAP"
    assert "PR #222" in data["question_text"]
    assert "리뷰 9001" in data["question_text"]
    assert "책임을 분리" in data["question_text"]
    assert ESCAPE_HATCH in data["question_text"]


def test_non_changes_requested_review_does_not_use_changes_question() -> None:
    """승인 리뷰는 CHANGES_REQUESTED 질문으로 오인하지 않는다."""
    response = _post(
        {
            "card_id": 132,
            "source_type": "PR",
            "candidate": _candidate(
                github_pr_number=222,
                reviews=[
                    _review(9002, 222, "전체 변경을 승인합니다.", state="APPROVED")
                ],
            ),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        }
    )

    data = response.json()["data"]
    assert data["question_type"] == "RECALL_AID"
    assert "PR #222" in data["question_text"]
    assert "리뷰 9002" not in data["question_text"]


def test_issue_feedback_creates_evidence_gap_question() -> None:
    """이슈가 전달되면 번호·제목·요약을 인용해 결과를 묻는다."""
    response = _post(
        {
            "card_id": 126,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(
                issues=[
                    _issue(
                        31,
                        "로그인 세션 유지 문제",
                        "새로고침하면 로그인이 해제됨",
                    )
                ]
            ),
            "missing_slots": [_slot(star_slot="R")],
            "existing_turn_count": 0,
        }
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["question_type"] == "EVIDENCE_GAP"
    assert "이슈 #31" in data["question_text"]
    assert "로그인 세션 유지 문제" in data["question_text"]
    assert "새로고침하면 로그인이 해제됨" in data["question_text"]
    assert ESCAPE_HATCH in data["question_text"]


def test_direct_slot_commit_precedes_review_and_issue_context() -> None:
    """카드 단위 리뷰·이슈가 있어도 슬롯 직접 근거를 먼저 사용한다."""
    response = _post(
        {
            "card_id": 127,
            "source_type": "PR",
            "candidate": _candidate(
                github_pr_number=222,
                reviews=[_review(9001, 222, "다른 기능의 책임 분리 요청")],
                issues=[_issue(31, "다른 기능 이슈")],
            ),
            "missing_slots": [
                _slot(
                    linked_commits=[
                        _commit(790, "abc123", "세션 테이블 인덱스 추가")
                    ]
                )
            ],
            "existing_turn_count": 0,
        }
    )

    question_text = response.json()["data"]["question_text"]
    assert "abc123" in question_text
    assert "리뷰 9001" not in question_text
    assert "이슈 #31" not in question_text


def test_medium_confidence_slot_is_accepted() -> None:
    """A와 DB가 사용하는 MEDIUM confidence를 B도 같은 값으로 받는다."""
    response = _post(
        {
            "card_id": 128,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(),
            "missing_slots": [_slot(confidence="MEDIUM")],
            "existing_turn_count": 0,
        }
    )

    assert response.status_code == 200
    assert response.json()["data"]["next_action"] == "ASK_AGAIN"


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
        {
            "card_id": 129,
            "source_type": "PR",
            "candidate": _candidate(
                github_pr_number=222,
                reviews=[_review(9001, 222, "책임을 분리해 주세요")],
            ),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        },
        {
            "card_id": 130,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(issues=[_issue(31, "세션 유지 문제")]),
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
    "candidate",
    [
        _candidate(
            reviews=[
                {
                    **_review(9001, 222, "책임을 분리해 주세요"),
                    "typo_field": "x",
                }
            ]
        ),
        _candidate(
            issues=[
                {
                    **_issue(31, "세션 유지 문제"),
                    "typo_field": "x",
                }
            ]
        ),
    ],
)
def test_unknown_review_and_issue_fields_are_rejected(candidate: dict) -> None:
    """리뷰·이슈 DTO도 알 수 없는 필드를 조용히 무시하지 않는다."""
    response = _post(
        {
            "card_id": 131,
            "source_type": "COMMIT_CLUSTER",
            "candidate": candidate,
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        }
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_review_state_requires_uppercase_enum() -> None:
    """리뷰 상태도 A/DB/FE 계약처럼 대문자 enum만 허용한다."""
    response = _post(
        {
            "card_id": 133,
            "source_type": "PR",
            "candidate": _candidate(
                github_pr_number=222,
                reviews=[
                    _review(
                        9001,
                        222,
                        "책임을 분리해 주세요",
                        state="changes_requested",
                    )
                ],
            ),
            "missing_slots": [_slot()],
            "existing_turn_count": 0,
        }
    )

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


def test_spring_owns_turn_sequence_and_max_turns() -> None:
    """AI는 순번을 반환하지 않고 Spring이 전달한 상한을 따른다."""
    response = _post(
        {
            "card_id": 106,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(_commit(790, "abc123", "세션 테이블 인덱스 추가")),
            "missing_slots": [_slot()],
            "existing_turn_count": 1,
            "max_turns": 1,
        }
    )

    data = response.json()["data"]
    assert data["next_action"] == "COMPLETE"
    assert data["question_text"] is None
    assert "turn_seq" not in data


def test_question_is_created_below_spring_max_turns() -> None:
    """기존 질문 수가 2여도 Spring 상한이 3이면 다음 질문을 만들 수 있다."""
    response = _post(
        {
            "card_id": 123,
            "source_type": "COMMIT_CLUSTER",
            "candidate": _candidate(
                _commit(790, "abc123", "세션 테이블 인덱스 추가")
            ),
            "missing_slots": [_slot()],
            "existing_turn_count": 2,
            "max_turns": 3,
        }
    )

    data = response.json()["data"]
    assert data["next_action"] == "ASK_AGAIN"
    assert "parent_turn_id" not in data


def test_max_turns_is_required_and_must_be_positive() -> None:
    """질문 상한의 단일 출처인 Spring은 유효한 max_turns를 반드시 보낸다."""
    base_payload = {
        "card_id": 124,
        "source_type": "DIRECT_CARD",
        "candidate": None,
        "missing_slots": [_slot()],
        "existing_turn_count": 0,
    }

    missing = client.post(ENDPOINT, json=base_payload)
    non_positive = _post({**base_payload, "max_turns": 0})

    assert missing.status_code == 400
    assert missing.json()["error"]["code"] == "INVALID_PAYLOAD"
    assert non_positive.status_code == 400
    assert non_positive.json()["error"]["code"] == "INVALID_PAYLOAD"


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
