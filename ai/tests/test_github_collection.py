"""GitHub 수집 API의 무상태·보안·정규화 계약 테스트."""

from __future__ import annotations

import asyncio

import httpx
import pytest
from fastapi.testclient import TestClient

from api.github import get_github_collector
from main import app
from schemas.github import GithubCollectionRequest, GithubCollectionResult, ReadCoverage
from services.github_collector import GithubCollector

client = TestClient(app)
TOKEN = "github-test-token"
SHA = "a" * 40
NOW = "2026-09-23T01:00:00Z"


class _FakeCollector:
    """라우터가 헤더 토큰을 서비스에만 전달하는지 확인한다."""

    def __init__(self) -> None:
        self.received_token: str | None = None

    async def collect(self, request, github_token: str) -> GithubCollectionResult:
        self.received_token = github_token
        return GithubCollectionResult(
            owner=request.owner,
            repository=request.repository,
            default_branch="main",
            branches=["main"],
            commits=[],
            pull_requests=[],
            issues=[],
            coverage=ReadCoverage(
                commits_read=0, pull_requests_read=0, issues_read=0
            ),
        )


@pytest.fixture
def fake_collector():
    collector = _FakeCollector()
    app.dependency_overrides[get_github_collector] = lambda: collector
    yield collector
    app.dependency_overrides.clear()


def test_collection_route_requires_header_and_never_returns_token(fake_collector) -> None:
    payload = {
        "owner": "kakaotechcampus-4",
        "repository": "ktc4-kyungpook-1",
        "target_login": "tester",
    }

    missing = client.post("/internal/github/collect", json=payload)
    response = client.post(
        "/internal/github/collect",
        json=payload,
        headers={"X-GitHub-Token": TOKEN},
    )

    assert missing.status_code == 400
    assert missing.json()["error"]["code"] == "INVALID_PAYLOAD"
    assert response.status_code == 200
    assert fake_collector.received_token == TOKEN
    assert TOKEN not in response.text


def test_collector_sends_bearer_token_and_normalizes_github_data() -> None:
    seen_authorization_headers: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_authorization_headers.append(request.headers["Authorization"])
        path = request.url.path
        if path == "/repos/acme/demo":
            return httpx.Response(200, json={"default_branch": "main"})
        if path.endswith("/branches"):
            return httpx.Response(200, json=[{"name": "main"}, {"name": "feature"}])
        if path.endswith("/commits"):
            branch = request.url.params["sha"]
            sha = SHA if branch == "main" else "b" * 40
            return httpx.Response(
                200,
                json=[
                    {
                        "sha": sha,
                        "html_url": f"https://github.com/acme/demo/commit/{sha}",
                        "author": {"login": "tester"},
                        "parents": [{"sha": "parent"}],
                        "commit": {
                            "message": f"feat: {branch}",
                            "author": {"name": "Tester", "date": NOW},
                        },
                    }
                ],
            )
        if path.endswith("/pulls"):
            return httpx.Response(
                200,
                json=[
                    {
                        "number": 7,
                        "title": "JWT 인증",
                        "state": "closed",
                        "user": {"login": "tester"},
                        "created_at": NOW,
                        "updated_at": NOW,
                        "merged_at": NOW,
                        "base": {"ref": "main"},
                        "head": {"ref": "feature"},
                        "html_url": "https://github.com/acme/demo/pull/7",
                    }
                ],
            )
        if path.endswith("/issues"):
            return httpx.Response(
                200,
                json=[
                    {
                        "number": 7,
                        "title": "PR row",
                        "state": "closed",
                        "user": {"login": "tester"},
                        "labels": [],
                        "created_at": NOW,
                        "updated_at": NOW,
                        "closed_at": NOW,
                        "html_url": "https://github.com/acme/demo/pull/7",
                        "pull_request": {},
                    },
                    {
                        "number": 8,
                        "title": "인증 오류",
                        "state": "open",
                        "user": {"login": "tester"},
                        "labels": [{"name": "bug"}],
                        "created_at": NOW,
                        "updated_at": NOW,
                        "closed_at": None,
                        "html_url": "https://github.com/acme/demo/issues/8",
                    },
                ],
            )
        raise AssertionError(f"unexpected path: {path}")

    collector = GithubCollector(transport=httpx.MockTransport(handler))
    request = GithubCollectionRequest(
        owner="acme", repository="demo", target_login="tester"
    )
    result = asyncio.run(collector.collect(request, TOKEN))

    assert seen_authorization_headers
    assert set(seen_authorization_headers) == {f"Bearer {TOKEN}"}
    assert result.default_branch == "main"
    assert result.branches == ["main", "feature"]
    assert len(result.commits) == 2
    assert len(result.pull_requests) == 1
    assert [issue.number for issue in result.issues] == [8]
    assert result.issues[0].labels == ["bug"]
    assert result.partial is False
    assert TOKEN not in result.model_dump_json()
    assert TOKEN not in repr(vars(collector))


def test_rate_limit_returns_partial_result_without_losing_collected_scope() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/repos/acme/demo":
            return httpx.Response(200, json={"default_branch": "main"})
        if path.endswith("/branches"):
            return httpx.Response(200, json=[{"name": "main"}])
        if path.endswith("/commits"):
            return httpx.Response(
                403,
                headers={"X-RateLimit-Remaining": "0", "Retry-After": "30"},
                json={"message": "rate limit"},
            )
        raise AssertionError(f"unexpected path: {path}")

    collector = GithubCollector(transport=httpx.MockTransport(handler))
    request = GithubCollectionRequest(
        owner="acme", repository="demo", target_login="tester"
    )
    result = asyncio.run(collector.collect(request, TOKEN))

    assert result.partial is True
    assert result.partial_reasons == ["GITHUB_RATE_LIMITED"]
    assert result.retry_after_sec == 30
    assert result.coverage.commits_read == 0


@pytest.mark.parametrize(
    ("github_status", "github_headers", "status_code", "code", "retryable"),
    [
        (401, {}, 502, "GITHUB_AUTH_FAILED", False),
        (403, {"X-RateLimit-Remaining": "4999"}, 502, "GITHUB_PERMISSION_DENIED", False),
        (404, {}, 502, "GITHUB_REPO_NOT_FOUND", False),
        (500, {}, 502, "GITHUB_API_ERROR", True),
        (403, {"X-RateLimit-Remaining": "0"}, 503, "GITHUB_API_ERROR", True),
        (403, {"Retry-After": "60"}, 503, "GITHUB_API_ERROR", True),
    ],
)
def test_github_failures_map_to_distinct_error_codes(
    github_status, github_headers, status_code, code, retryable
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            github_status, headers=github_headers, json={"message": "failure"}
        )

    collector = GithubCollector(transport=httpx.MockTransport(handler))
    app.dependency_overrides[get_github_collector] = lambda: collector
    try:
        response = client.post(
            "/internal/github/collect",
            json={"owner": "acme", "repository": "demo", "target_login": "tester"},
            headers={"X-GitHub-Token": TOKEN},
        )
    finally:
        app.dependency_overrides.clear()

    body = response.json()
    assert response.status_code == status_code
    assert body["success"] is False
    assert body["error"]["code"] == code
    assert body["error"]["retryable"] is retryable
    assert TOKEN not in response.text
