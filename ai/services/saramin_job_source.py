"""사람인 공식 채용공고 검색 API 구현체.

공식 API는 공고 본문 전체가 아니라 제목·직무코드·키워드 등의 메타데이터를
제공하므로 이 결과를 곧바로 RAG 직무 조건으로 인덱싱하지 않는다.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from typing import Any, Optional

import httpx

from ports.job_posting_source import JobPostingSourceExecution
from schemas.matching import (
    DiscoveredJobPosting,
    JobPostingDiscoveryRequest,
    JobPostingDiscoveryResult,
    JobPostingStatus,
)


class JobPostingSourceError(RuntimeError):
    """외부 채용공고 제공자가 정상 응답을 반환하지 않은 경우."""


class SaraminJobPostingSource:
    """access key를 프로세스 환경에서 읽고 요청 범위에만 사용하는 어댑터."""

    def __init__(
        self,
        *,
        access_key: Optional[str] = None,
        base_url: str = "https://oapi.saramin.co.kr",
        transport: Optional[httpx.AsyncBaseTransport] = None,
        timeout_seconds: float = 30.0,
    ) -> None:
        self._access_key = (access_key or os.getenv("SARAMIN_ACCESS_KEY", "")).strip()
        self._base_url = base_url
        self._transport = transport
        self._timeout_seconds = timeout_seconds

    async def discover(
        self,
        request: JobPostingDiscoveryRequest,
    ) -> JobPostingSourceExecution:
        if not self._access_key:
            raise JobPostingSourceError("SARAMIN_ACCESS_KEY가 필요합니다.")

        params: dict[str, Any] = {
            "access-key": self._access_key,
            "start": request.page,
            "count": request.count,
            "sort": "ud",
            "fields": "posting-date expiration-date keyword-code",
        }
        if request.keywords:
            params["keywords"] = ",".join(request.keywords)
        if request.job_mid_codes:
            params["job_mid_cd"] = ",".join(request.job_mid_codes)
        if request.updated_min:
            params["updated_min"] = int(request.updated_min.timestamp())
        if request.updated_max:
            params["updated_max"] = int(request.updated_max.timestamp())

        async with httpx.AsyncClient(
            base_url=self._base_url,
            headers={"Accept": "application/json", "User-Agent": "Gitory/1.0"},
            transport=self._transport,
            timeout=self._timeout_seconds,
        ) as client:
            try:
                response = await client.get("/job-search", params=params)
                response.raise_for_status()
                payload = response.json()
            except (httpx.HTTPError, ValueError) as exc:
                raise JobPostingSourceError("사람인 채용공고를 수집하지 못했습니다.") from exc

        if "code" in payload:
            raise JobPostingSourceError(str(payload.get("message") or "사람인 API 오류"))

        jobs = payload.get("jobs") or {}
        raw_items = jobs.get("job") or []
        if isinstance(raw_items, dict):
            raw_items = [raw_items]
        postings = [self._normalize(item) for item in raw_items]
        return JobPostingSourceExecution(
            result=JobPostingDiscoveryResult(
                page=int(jobs.get("start", request.page)),
                count=int(jobs.get("count", len(postings))),
                total=int(jobs.get("total", len(postings))),
                postings=postings,
            ),
            api_calls=1,
        )

    @classmethod
    def _normalize(cls, raw: dict[str, Any]) -> DiscoveredJobPosting:
        company = (raw.get("company") or {}).get("detail") or {}
        position = raw.get("position") or {}
        return DiscoveredJobPosting(
            external_posting_id=str(raw["id"]),
            company_name=str(company.get("name") or "미상"),
            job_title=str(position["title"]),
            job_category=cls._nested_name(position, "job-mid-code"),
            job_codes=cls._split_csv(cls._nested_name(position, "job-code")),
            keywords=cls._split_csv(raw.get("keyword")),
            experience=cls._nested_name(position, "experience-level"),
            education=cls._nested_name(position, "required-education-level"),
            employment_type=cls._nested_name(position, "job-type"),
            location=cls._nested_name(position, "location"),
            source_url=raw["url"],
            published_at=cls._timestamp(raw.get("posting-timestamp")),
            updated_at=cls._timestamp(raw.get("modification-timestamp")),
            expires_at=cls._timestamp(raw.get("expiration-timestamp")),
            status=JobPostingStatus.ACTIVE if int(raw.get("active", 0)) == 1 else JobPostingStatus.EXPIRED,
        )

    @staticmethod
    def _nested_name(parent: dict[str, Any], key: str) -> Optional[str]:
        value = parent.get(key)
        if isinstance(value, dict):
            name = value.get("name")
            return str(name).strip() if name else None
        return str(value).strip() if value else None

    @staticmethod
    def _split_csv(value: Optional[str]) -> list[str]:
        if not value:
            return []
        return [item.strip() for item in str(value).split(",") if item.strip()]

    @staticmethod
    def _timestamp(value: Any) -> Optional[datetime]:
        if value in (None, ""):
            return None
        return datetime.fromtimestamp(int(value), tz=timezone.utc)
