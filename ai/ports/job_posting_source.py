"""외부 채용공고 제공자를 감추는 수집 포트."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

from schemas.matching import JobPostingDiscoveryRequest, JobPostingDiscoveryResult


@dataclass(frozen=True)
class JobPostingSourceExecution:
    result: JobPostingDiscoveryResult
    api_calls: int


@runtime_checkable
class JobPostingSourcePort(Protocol):
    async def discover(
        self,
        request: JobPostingDiscoveryRequest,
    ) -> JobPostingSourceExecution:
        """공식 API에서 저장 후보 공고 메타데이터를 수집한다."""
