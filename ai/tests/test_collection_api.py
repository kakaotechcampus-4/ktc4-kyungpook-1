"""``POST /internal/collect`` API 계약 테스트."""

from __future__ import annotations

from fastapi.testclient import TestClient

from api.collection import get_repository_activity_port
from main import app
from ports.repository_activity import RepositoryActivityExecution
from schemas.collection import CandidateDetailResult, RepositoryCollectionResult


class FakeActivityPort:
    """헤더 토큰을 외부로 내보내지 않고 전달 여부만 기록한다."""

    def __init__(self) -> None:
        self.received_token = None
        self.received_detail_token = None

    async def collect(self, request, github_token):
        self.received_token = github_token
        return RepositoryActivityExecution(
            result=RepositoryCollectionResult(
                user_repository_id=request.user_repository_id
            ),
            api_calls=3,
        )

    async def fetch_candidate_details(self, request, github_token):
        self.received_detail_token = github_token
        return RepositoryActivityExecution(
            result=CandidateDetailResult(
                user_repository_id=request.user_repository_id,
                github_pr_number=request.github_pr_number,
            ),
            api_calls=2,
        )


def _payload() -> dict:
    return {
        "user_repository_id": 123,
        "repository": {
            "github_repo_id": 456789,
            "owner_login": "grow22",
            "name": "gitory",
            "default_branch": "develop",
        },
        "actor": {
            "github_login": "grow22",
            "known_emails": ["grow22@example.com"],
        },
    }


def _detail_payload() -> dict:
    return {
        "user_repository_id": 123,
        "repository": _payload()["repository"],
        "commit_shas": ["abc1234567890"],
        "github_pr_number": 17,
    }


def test_collect_endpoint_passes_header_token_only_to_collector() -> None:
    fake = FakeActivityPort()
    app.dependency_overrides[get_repository_activity_port] = lambda: fake
    try:
        response = TestClient(app).post(
            "/internal/collect",
            json=_payload(),
            headers={"X-GitHub-Token": "secret-only-in-memory"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert fake.received_token == "secret-only-in-memory"
    body = response.json()
    assert body["success"] is True
    assert body["meta"]["tool_calls_made"] == 3
    assert "secret-only-in-memory" not in response.text


def test_candidate_detail_endpoint_passes_header_token_only_to_port() -> None:
    fake = FakeActivityPort()
    app.dependency_overrides[get_repository_activity_port] = lambda: fake
    try:
        response = TestClient(app).post(
            "/internal/collect/candidate-details",
            json=_detail_payload(),
            headers={"X-GitHub-Token": "detail-secret-only-in-memory"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert fake.received_detail_token == "detail-secret-only-in-memory"
    assert response.json()["meta"]["tool_calls_made"] == 2
    assert "detail-secret-only-in-memory" not in response.text


def test_collect_endpoint_rejects_missing_github_token() -> None:
    response = TestClient(app).post("/internal/collect", json=_payload())

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_collect_endpoint_rejects_unknown_request_field() -> None:
    payload = {**_payload(), "github_token": "must-not-be-in-json"}

    response = TestClient(app).post(
        "/internal/collect",
        json=payload,
        headers={"X-GitHub-Token": "header-token"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_collect_endpoint_rejects_removed_since_field() -> None:
    payload = {**_payload(), "since": "2026-09-20T09:00:00Z"}

    response = TestClient(app).post(
        "/internal/collect",
        json=payload,
        headers={"X-GitHub-Token": "header-token"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_candidate_detail_requires_sha_or_pr_number() -> None:
    payload = _detail_payload()
    payload["commit_shas"] = []
    payload["github_pr_number"] = None

    response = TestClient(app).post(
        "/internal/collect/candidate-details",
        json=payload,
        headers={"X-GitHub-Token": "header-token"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"
