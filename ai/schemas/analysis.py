"""커밋 선별·경험 그룹화·diff 분석·STAR 생성 모델."""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field

from schemas.common import (
    AnalysisVerdict,
    Confidence,
    SourceType,
    StarSlot,
    StarStatus,
)


class ChangedFile(BaseModel):
    """커밋에 포함된 파일 변경 메타데이터."""

    path: str
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    patch: Optional[str] = Field(
        None, description="상한을 넘거나 바이너리인 경우 본문 없이 전달"
    )


class CommitInput(BaseModel):
    """Spring이 GitHub에서 수집해 전달하는 정규화 커밋."""

    sha: str = Field(..., min_length=7, max_length=40)
    message: str
    author_login: Optional[str] = None
    authored_at: datetime
    parent_count: int = Field(ge=0)
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    files: list[ChangedFile] = Field(default_factory=list)
    pull_request_number: Optional[int] = None
    issue_numbers: list[int] = Field(default_factory=list)


class ExperienceGroupingRequest(BaseModel):
    """커밋 선별과 경험 그룹화를 요청한다."""

    repository_id: str
    target_login: str
    commits: list[CommitInput]


class ExperienceCandidate(BaseModel):
    """같은 기능의 커밋을 묶은 경험 후보."""

    group_key: str
    source_type: SourceType
    source_ref: Optional[str] = None
    title: str
    reason: str
    score: float = Field(ge=0.0, le=1.0)
    low_card_worth: bool = False
    commit_shas: list[str]


class ExperienceGroupingResponse(BaseModel):
    """사용자가 확정할 수 있는 경험 후보 목록."""

    verdict: AnalysisVerdict
    candidates: list[ExperienceCandidate] = Field(default_factory=list)
    excluded_commit_shas: list[str] = Field(default_factory=list)


class DiffEvidence(BaseModel):
    """확정 경험에서 추출한 최소 diff 근거."""

    sha: str = Field(..., min_length=7, max_length=40)
    message: str
    author_login: Optional[str] = None
    churn: str = Field(description="예: +71/-0")
    summary: str = Field(description="diff에서 직접 확인한 변경 요약")
    url: Optional[str] = None


class DependencyFile(BaseModel):
    """기술 스택 판별에 필요한 의존성·설정 파일."""

    path: str
    content: str


class StarAnalysisRequest(BaseModel):
    """확정된 경험의 STAR 분석을 요청한다."""

    candidate_id: str
    target_login: str
    title: str
    source_type: SourceType
    source_ref: Optional[str] = None
    evidence: list[DiffEvidence]
    dependency_files: list[DependencyFile] = Field(default_factory=list)
    confirmed_answers: list[str] = Field(default_factory=list)


class EvidenceReference(BaseModel):
    """STAR 문장에 연결된 커밋 근거."""

    sha: str = Field(..., min_length=7, max_length=40)
    message: str
    url: Optional[str] = None


class StarFieldResult(BaseModel):
    """STAR 한 영역의 분석 결과."""

    text: Optional[str] = None
    status: StarStatus
    confidence: Optional[Confidence] = None
    evidence: list[EvidenceReference] = Field(default_factory=list)
    insufficient_reason: Optional[str] = None
    review_reason: Optional[str] = None


class StarAnalysisResponse(BaseModel):
    """근거 검증을 마친 STAR 분석 결과."""

    title: str
    star: dict[StarSlot, StarFieldResult]
    missing_fields: list[StarSlot] = Field(default_factory=list)
    shared_with: Optional[str] = None
    removed_claims: list[str] = Field(default_factory=list)
