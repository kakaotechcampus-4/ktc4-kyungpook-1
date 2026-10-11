"""사람인 공식 채용공고 API 어댑터 테스트."""

from __future__ import annotations

import asyncio

import httpx
import pytest

from ports.job_posting_source import JobPostingSourcePort
from schemas.matching import JobPostingDiscoveryRequest
from services.saramin_job_source import JobPostingSourceError, SaraminJobPostingSource


def _response_payload() -> dict:
    return {
        "jobs": {
            "count": 1,
            "start": 0,
            "total": "1",
            "job": [
                {
                    "id": "27614114",
                    "url": "https://www.saramin.co.kr/zf_user/jobs/relay/view?rec_idx=27614114",
                    "active": 1,
                    "company": {"detail": {"name": "예시회사"}},
                    "position": {
                        "title": "백엔드 개발자",
                        "location": {"code": "101000", "name": "서울"},
                        "job-type": {"code": "1", "name": "정규직"},
                        "job-mid-code": {"code": "22", "name": "IT개발·데이터"},
                        "job-code": {"code": "84,87", "name": "백엔드,API"},
                        "experience-level": {"code": 1, "name": "신입"},
                        "required-education-level": {"code": "0", "name": "학력무관"},
                    },
                    "keyword": "Java,Spring Boot,REST API",
                    "posting-timestamp": "1791331200",
                    "modification-timestamp": "1791417600",
                    "expiration-timestamp": "1793923199",
                }
            ],
        }
    }


def test_saramin_source_normalizes_metadata_without_inventing_full_requirements() -> None:
    seen_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal seen_request
        seen_request = request
        return httpx.Response(200, json=_response_payload())

    source = SaraminJobPostingSource(
        access_key="secret-access-key",
        base_url="https://saramin.test",
        transport=httpx.MockTransport(handler),
    )
    request = JobPostingDiscoveryRequest(
        keywords=["백엔드", "AI"],
        job_mid_codes=["22"],
        updated_min="2026-10-01T00:00:00Z",
        page=0,
        count=100,
    )

    execution = asyncio.run(source.discover(request))

    assert execution.api_calls == 1
    assert execution.result.total == 1
    posting = execution.result.postings[0]
    assert posting.external_posting_id == "27614114"
    assert posting.company_name == "예시회사"
    assert posting.job_codes == ["백엔드", "API"]
    assert posting.keywords == ["Java", "Spring Boot", "REST API"]
    assert posting.status == "ACTIVE"
    assert not hasattr(posting, "required_qualifications")
    assert seen_request is not None
    assert seen_request.url.params["access-key"] == "secret-access-key"
    assert seen_request.url.params["sort"] == "ud"
    assert seen_request.url.params["job_mid_cd"] == "22"


def test_saramin_source_supports_single_job_object_and_expired_status() -> None:
    payload = _response_payload()
    job = payload["jobs"]["job"][0]
    job["active"] = 0
    payload["jobs"]["job"] = job

    source = SaraminJobPostingSource(
        access_key="key",
        base_url="https://saramin.test",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=payload)),
    )

    result = asyncio.run(source.discover(JobPostingDiscoveryRequest())).result

    assert len(result.postings) == 1
    assert result.postings[0].status == "EXPIRED"


def test_saramin_source_rejects_missing_key() -> None:
    source = SaraminJobPostingSource(access_key="")

    with pytest.raises(JobPostingSourceError, match="SARAMIN_ACCESS_KEY"):
        asyncio.run(source.discover(JobPostingDiscoveryRequest()))


def test_saramin_source_converts_provider_error_without_leaking_key() -> None:
    source = SaraminJobPostingSource(
        access_key="never-leak-this-key",
        base_url="https://saramin.test",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                json={"code": 4, "message": "일일 최대 요청 가능 횟수 초과"},
            )
        ),
    )

    with pytest.raises(JobPostingSourceError) as exc_info:
        asyncio.run(source.discover(JobPostingDiscoveryRequest()))

    assert "never-leak-this-key" not in str(exc_info.value)


def test_saramin_source_satisfies_port() -> None:
    assert isinstance(SaraminJobPostingSource(access_key="key"), JobPostingSourcePort)
