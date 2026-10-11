"""채용공고 수집 내부 API 계약 테스트."""

from __future__ import annotations

from fastapi.testclient import TestClient

from api.job_postings import get_job_posting_source
from main import app
from ports.job_posting_source import JobPostingSourceExecution
from schemas.matching import JobPostingDiscoveryResult
from services.saramin_job_source import JobPostingSourceError


class FakeJobPostingSource:
    async def discover(self, request):
        return JobPostingSourceExecution(
            result=JobPostingDiscoveryResult(
                page=request.page,
                count=0,
                total=0,
                postings=[],
            ),
            api_calls=1,
        )


class FailingJobPostingSource:
    async def discover(self, request):
        raise JobPostingSourceError("secret provider detail")


def test_job_posting_discovery_route_uses_internal_envelope() -> None:
    app.dependency_overrides[get_job_posting_source] = FakeJobPostingSource
    try:
        response = TestClient(app).post(
            "/internal/job-postings/discover",
            json={"keywords": ["백엔드"], "page": 0, "count": 100},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["success"] is True
    assert response.json()["data"]["source"] == "SARAMIN"
    assert response.json()["meta"]["tool_calls_made"] == 1


def test_job_posting_discovery_rejects_invalid_page_size() -> None:
    app.dependency_overrides[get_job_posting_source] = FakeJobPostingSource
    try:
        response = TestClient(app).post(
            "/internal/job-postings/discover",
            json={"count": 111},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_job_posting_provider_failure_is_sanitized() -> None:
    app.dependency_overrides[get_job_posting_source] = FailingJobPostingSource
    try:
        response = TestClient(app).post(
            "/internal/job-postings/discover",
            json={},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 502
    assert response.json()["error"]["retryable"] is True
    assert "secret provider detail" not in response.text
