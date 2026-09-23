"""A 파트 분석 API가 공통 내부 계약을 지키는지 검증한다."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from api.analysis import get_analysis_pipeline
from main import app
from schemas.analysis import (
    CommitInput,
    EvidenceReference,
    ExperienceGroupingResponse,
    StarAnalysisResponse,
    StarFieldResult,
)

client = TestClient(app)


class _FakeAnalysisPipeline:
    """라우터 봉투 계약만 검증하기 위한 테스트 대역."""

    async def group_experiences(self, request) -> ExperienceGroupingResponse:
        return ExperienceGroupingResponse(verdict="PARTIAL")

    async def analyze_star(self, request) -> StarAnalysisResponse:
        return StarAnalysisResponse(
            title=request.title,
            star={
                "S": StarFieldResult(
                    text="인증 흐름을 정리해야 하는 상황이었다.",
                    status="FILLED",
                    confidence="HIGH",
                ),
                "T": StarFieldResult(status="EMPTY"),
                "A": StarFieldResult(status="NEEDS_REVIEW", confidence="LOW"),
                "R": StarFieldResult(status="EMPTY"),
            },
            missing_fields=["T", "R"],
        )


@pytest.fixture(autouse=True)
def override_analysis_pipeline():
    """각 테스트에서 실제 미구현 서비스를 호출하지 않는다."""
    app.dependency_overrides[get_analysis_pipeline] = _FakeAnalysisPipeline
    yield
    app.dependency_overrides.clear()


def test_analysis_routes_are_registered_under_internal_prefix() -> None:
    paths = {route.path for route in app.routes}

    assert "/internal/analysis/groups" in paths
    assert "/internal/analysis/star" in paths
    assert "/analysis/groups" not in paths


def test_grouping_response_uses_envelope_and_allows_partial() -> None:
    response = client.post(
        "/internal/analysis/groups",
        json={"repository_id": "repo-1", "target_login": "user", "commits": []},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["verdict"] == "PARTIAL"
    assert body["error"] is None
    assert body["meta"]["processing_ms"] >= 0


def test_star_response_uses_filled_and_two_confidence_values() -> None:
    response = client.post(
        "/internal/analysis/star",
        json={
            "candidate_id": "candidate-1",
            "target_login": "user",
            "title": "JWT 인증 구현",
            "source_type": "COMMIT_CLUSTER",
            "evidence": [],
        },
    )

    assert response.status_code == 200
    star = response.json()["data"]["star"]
    assert star["S"]["status"] == "FILLED"
    assert star["S"]["confidence"] == "HIGH"
    assert star["A"]["confidence"] == "LOW"


@pytest.mark.parametrize(
    ("field", "value"),
    [("status", "GENERATED"), ("confidence", "MEDIUM")],
)
def test_invalid_star_contract_values_are_rejected(field: str, value: str) -> None:
    payload = {"status": "FILLED", field: value}

    with pytest.raises(ValidationError):
        StarFieldResult.model_validate(payload)


def test_invalid_verdict_is_rejected() -> None:
    with pytest.raises(ValidationError):
        ExperienceGroupingResponse(verdict="DONE")


def test_commit_authored_at_is_iso_datetime() -> None:
    with pytest.raises(ValidationError):
        CommitInput(
            sha="abcdef1",
            message="feat: 인증 구현",
            authored_at="어제",
            parent_count=1,
            additions=1,
            deletions=0,
        )


def test_evidence_reference_validates_sha_length() -> None:
    with pytest.raises(ValidationError):
        EvidenceReference(sha="abc", message="짧은 SHA")
