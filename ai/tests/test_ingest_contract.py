"""Spring ↔ AI GitHub 수집 공통 계약 검증."""

import json
from pathlib import Path

from schemas.analysis import CommitInput
from schemas.collection import (
    CandidateDetailRequest,
    CandidateDetailResult,
    RepositoryCollectionRequest,
    RepositoryCollectionResult,
)


CONTRACT_PATH = (
    Path(__file__).resolve().parents[2]
    / "contracts"
    / "ingest"
    / "repository-activity.json"
)
DETAIL_CONTRACT_PATH = (
    Path(__file__).resolve().parents[2]
    / "contracts"
    / "ingest"
    / "candidate-details.json"
)


def test_collection_contract_matches_ai_models() -> None:
    """대표 요청과 응답을 AI의 Pydantic 계약이 그대로 받아들인다."""
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))

    request = RepositoryCollectionRequest.model_validate(
        contract["spring_to_ai"]["request"]
    )
    result = RepositoryCollectionResult.model_validate(
        contract["ai_to_spring"]["data"]
    )

    assert request.repository.owner_login == "grow22"
    assert request.collection_branches == ["feature/session-index"]
    assert result.commits[0].pull_request_number == 17
    assert result.pull_requests[0].reviews[0].state == "CHANGES_REQUESTED"
    assert result.pull_requests[0].linked_issue_numbers == [42]
    assert (
        result.pull_requests[0].reviews[0].comments[0].path
        == "backend/session.sql"
    )
    assert result.issues[0].body_excerpt == "응답 시간이 오래 걸립니다."


def test_collected_commit_can_feed_a_analysis() -> None:
    """A-1 수집 결과가 A-2 분석 입력으로 손실 없이 이어진다."""
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    collected = contract["ai_to_spring"]["data"]["commits"][0]

    commit = CommitInput.model_validate(collected)

    assert commit.sha == "abc1234567890"
    assert commit.pull_request_number == 17
    assert commit.issue_numbers == [42]
    assert commit.files[1].patch is None


def test_collection_contract_does_not_persist_full_bodies_or_diff_patch() -> None:
    """초기 수집 계약은 A 입력용 excerpt만 두고 무거운 원문은 전달하지 않는다."""
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    result = contract["ai_to_spring"]["data"]

    assert "patch" not in result["commits"][0]["files"][0]
    assert "body" not in result["pull_requests"][0]
    assert "body" not in result["pull_requests"][0]["reviews"][0]
    assert "body" not in result["issues"][0]


def test_contract_does_not_contain_oauth_token_value() -> None:
    """공유 fixture에는 실제 또는 예시 OAuth 토큰 값도 저장하지 않는다."""
    contract_text = CONTRACT_PATH.read_text(encoding="utf-8").lower()

    assert "bearer " not in contract_text
    assert "ghp_" not in contract_text
    assert "github_pat_" not in contract_text


def test_candidate_detail_contract_matches_ai_models() -> None:
    """선택 후보 상세 요청·응답이 포트 DTO와 일치한다."""
    contract = json.loads(DETAIL_CONTRACT_PATH.read_text(encoding="utf-8"))

    request = CandidateDetailRequest.model_validate(
        contract["spring_to_ai"]["request"]
    )
    result = CandidateDetailResult.model_validate(
        contract["ai_to_spring"]["data"]
    )

    assert request.commit_shas == ["abc1234567890"]
    assert result.commits[0].files[0].patch is not None
    assert result.commits[0].files[1].patch is None


def test_candidate_detail_contract_does_not_contain_oauth_token_value() -> None:
    """상세 diff 계약에도 OAuth 토큰 값을 넣지 않는다."""
    contract_text = DETAIL_CONTRACT_PATH.read_text(encoding="utf-8").lower()

    assert "bearer " not in contract_text
    assert "ghp_" not in contract_text
    assert "github_pat_" not in contract_text
