"""기업·직무 공고 인덱싱과 확정 STAR 근거 검색 계약.

Spring은 채용공고 원본과 상태의 단일 출처이고, AI는 활성 공고의 검색용
파생 인덱스만 관리한다. 지원자의 STAR 문장은 저장하지 않고 요청 동안만
임베딩해 유사한 직무 조건을 찾는다.
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Optional

from pydantic import AnyHttpUrl, BaseModel, ConfigDict, Field, model_validator

from schemas.common import StarSlot


class StrictModel(BaseModel):
    """오탈자 필드를 조용히 버리지 않는 내부 계약 모델."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class JobPostingStatus(str, Enum):
    ACTIVE = "ACTIVE"
    EXPIRED = "EXPIRED"
    INACTIVE = "INACTIVE"


class CriterionType(str, Enum):
    RESPONSIBILITY = "RESPONSIBILITY"
    REQUIRED = "REQUIRED"
    PREFERRED = "PREFERRED"
    SKILL = "SKILL"


class JobPostingContentStatus(str, Enum):
    """정밀 매칭에 사용할 상세 본문 정규화 상태."""

    METADATA_ONLY = "METADATA_ONLY"
    READY = "READY"


class ExtractionOutcome(str, Enum):
    """본문 해시 비교를 포함한 상세 조건 추출 결과."""

    UPDATED = "UPDATED"
    UNCHANGED = "UNCHANGED"


class JobCriterion(StrictModel):
    """공고 원문에서 확인한 한 가지 직무 조건과 정확한 인용."""

    criterion_type: CriterionType
    text: str = Field(min_length=1, max_length=1000)
    source_quote: str = Field(min_length=1, max_length=4000)


class JobPosting(StrictModel):
    """Spring이 저장한 채용공고를 AI 검색 인덱스에 반영하는 입력."""

    source: str = Field(min_length=1, max_length=50)
    external_posting_id: str = Field(min_length=1, max_length=255)
    company_name: str = Field(min_length=1, max_length=255)
    job_title: str = Field(min_length=1, max_length=255)
    job_category: str = Field(min_length=1, max_length=100)
    content_status: JobPostingContentStatus = JobPostingContentStatus.READY
    criteria: list[JobCriterion] = Field(default_factory=list)
    responsibilities: list[str] = Field(default_factory=list)
    required_qualifications: list[str] = Field(default_factory=list)
    preferred_qualifications: list[str] = Field(default_factory=list)
    skills: list[str] = Field(default_factory=list)
    experience: Optional[str] = None
    education: Optional[str] = None
    employment_type: Optional[str] = None
    location: Optional[str] = None
    source_url: AnyHttpUrl
    published_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
    expires_at: Optional[datetime] = None
    collected_at: datetime
    status: JobPostingStatus
    content_hash: str = Field(min_length=8, max_length=128)

    @model_validator(mode="after")
    def require_matchable_criteria(self) -> "JobPosting":
        """활성 공고에는 검색 가능한 조건을 하나 이상 요구한다."""
        criteria = (
            [item.text for item in self.criteria]
            + self.responsibilities
            + self.required_qualifications
            + self.preferred_qualifications
            + self.skills
        )
        if (
            self.status == JobPostingStatus.ACTIVE
            and self.content_status == JobPostingContentStatus.READY
            and not any(item.strip() for item in criteria)
        ):
            raise ValueError("활성 공고에는 검색 가능한 직무 조건이 필요합니다.")
        if self.content_status == JobPostingContentStatus.METADATA_ONLY and criteria:
            raise ValueError("METADATA_ONLY 공고에는 상세 직무 조건을 넣을 수 없습니다.")
        return self

    @property
    def posting_key(self) -> str:
        return f"{self.source}:{self.external_posting_id}"


class JobPostingIndexRequest(StrictModel):
    """Spring 저장 완료 후 AI 파생 인덱스를 갱신하는 요청."""

    postings: list[JobPosting] = Field(min_length=1, max_length=200)


class JobPostingIndexResult(StrictModel):
    indexed_postings: int = Field(ge=0)
    indexed_criteria: int = Field(ge=0)
    deactivated_postings: int = Field(ge=0)
    skipped_metadata_only: int = Field(default=0, ge=0)


class ConfirmedStarStatement(StrictModel):
    """Spring이 사용자 확정 여부를 검증한 STAR 문장."""

    card_id: int = Field(gt=0, strict=True)
    star_slot: StarSlot
    statement_seq: int = Field(gt=0, strict=True)
    body: str = Field(min_length=1, max_length=4000)


class MatchingRequest(StrictModel):
    """확정 STAR 문장으로 활성 공고의 조건을 검색하는 요청."""

    statements: list[ConfirmedStarStatement] = Field(min_length=1, max_length=40)
    job_categories: list[str] = Field(default_factory=list, max_length=20)
    top_k: int = Field(default=10, ge=1, le=50, strict=True)


class CriterionMatch(StrictModel):
    criterion_type: CriterionType
    criterion_text: str
    source_quote: Optional[str] = None
    card_id: int = Field(gt=0)
    star_slot: StarSlot
    statement_seq: int = Field(gt=0)


class MatchedJobPosting(StrictModel):
    posting_key: str
    company_name: str
    job_title: str
    job_category: str
    source_url: AnyHttpUrl
    evidence: list[CriterionMatch] = Field(default_factory=list)


class MatchingResponse(StrictModel):
    matches: list[MatchedJobPosting] = Field(default_factory=list)


class JobPostingDetailExtractionRequest(StrictModel):
    """공개된 공고 상세 본문에서 직무 조건을 추출하는 요청."""

    source: str = Field(min_length=1, max_length=50)
    external_posting_id: str = Field(min_length=1, max_length=255)
    job_title: str = Field(min_length=1, max_length=255)
    source_url: AnyHttpUrl
    raw_text: str = Field(min_length=1, max_length=100_000)
    previous_content_hash: Optional[str] = Field(None, min_length=64, max_length=64)
    previous_content_status: JobPostingContentStatus = JobPostingContentStatus.READY


class JobPostingDetailExtractionResult(StrictModel):
    """Spring 저장과 Chroma 인덱싱에 사용할 검증 완료 조건."""

    source: str
    external_posting_id: str
    source_url: AnyHttpUrl
    content_hash: str = Field(min_length=64, max_length=64)
    outcome: ExtractionOutcome
    content_status: JobPostingContentStatus
    criteria: list[JobCriterion] = Field(default_factory=list)
