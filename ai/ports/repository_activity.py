"""A 분석 파이프라인이 사용하는 저장소 활동 포트."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Generic, Protocol, TypeVar, runtime_checkable

from schemas.collection import (
    CandidateDetailRequest,
    CandidateDetailResult,
    RepositoryCollectionRequest,
    RepositoryCollectionResult,
)


ResultT = TypeVar("ResultT")


@dataclass(frozen=True)
class RepositoryActivityExecution(Generic[ResultT]):
    """포트 결과와 외부 API 호출 횟수를 분리한 실행 결과."""

    result: ResultT
    api_calls: int


@runtime_checkable
class RepositoryActivityPort(Protocol):
    """GitHub 종속성 없이 A가 사용하는 저장소 활동 계약."""

    async def collect(
        self,
        request: RepositoryCollectionRequest,
        github_token: str,
    ) -> RepositoryActivityExecution[RepositoryCollectionResult]:
        """후보 그룹화용 저장소 활동을 수집한다."""

    async def fetch_candidate_details(
        self,
        request: CandidateDetailRequest,
        github_token: str,
    ) -> RepositoryActivityExecution[CandidateDetailResult]:
        """선택된 후보의 제한된 diff 상세를 조회한다."""
