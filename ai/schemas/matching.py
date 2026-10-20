"""기업·직무 공고 조건 뽑기, 조건 색인, 확정 STAR 문장 검색 계약.

Spring은 채용공고와 조건의 단일 출처이고, AI는 조건 id와 벡터만 가진 파생 색인을 관리한다.
지원자의 STAR 문장은 저장하지 않고 요청 동안만 임베딩해 가까운 조건 id를 돌려준다.
"""

from __future__ import annotations

from enum import Enum
from typing import Annotated, Optional

from pydantic import AnyHttpUrl, BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    """오탈자 필드를 조용히 버리지 않는 내부 계약 모델."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class JobPostingStatus(str, Enum):
    ACTIVE = "ACTIVE"
    EXPIRED = "EXPIRED"
    REMOVED = "REMOVED"


class JobCriterionType(str, Enum):
    """상세 본문에서 뽑는 조건의 종류.

    기술 항목은 속한 문단에 따라 필수·우대로 넣는다.
    """

    RESPONSIBILITY = "RESPONSIBILITY"
    REQUIRED = "REQUIRED"
    PREFERRED = "PREFERRED"


class JobCategory(str, Enum):
    BACKEND = "BACKEND"
    FRONTEND = "FRONTEND"
    MOBILE = "MOBILE"
    DEVOPS = "DEVOPS"
    DATA = "DATA"
    AI_ML = "AI_ML"
    SECURITY = "SECURITY"
    QA = "QA"
    ETC = "ETC"


class ExperienceLevel(str, Enum):
    ENTRY = "ENTRY"
    EXPERIENCED = "EXPERIENCED"
    UNSPECIFIED = "UNSPECIFIED"


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

    criterion_type: JobCriterionType
    text: str = Field(min_length=1, max_length=1000)
    source_quote: str = Field(min_length=1, max_length=4000)


class JobExperience(StrictModel):
    """공고에 적힌 경력 요건.

    ENTRY는 연차 없이 인용만, EXPERIENCED는 연차와 인용을, UNSPECIFIED는 둘 다 비운다.
    """

    level: ExperienceLevel
    min_years: Optional[int] = Field(default=None, ge=0, le=60, strict=True)
    source_quote: Optional[str] = Field(default=None, min_length=1, max_length=1000)

    @model_validator(mode="after")
    def check_level_fields(self) -> "JobExperience":
        if self.level == ExperienceLevel.UNSPECIFIED:
            if self.min_years is not None or self.source_quote is not None:
                raise ValueError("UNSPECIFIED 경력에는 min_years와 source_quote를 넣을 수 없습니다.")
            return self
        if self.source_quote is None:
            raise ValueError("경력 요건에는 원문 인용이 필요합니다.")
        if self.level == ExperienceLevel.ENTRY and self.min_years is not None:
            raise ValueError("ENTRY 경력에는 min_years를 넣을 수 없습니다.")
        if self.level == ExperienceLevel.EXPERIENCED and not self.min_years:
            raise ValueError("EXPERIENCED 경력에는 1 이상의 min_years가 필요합니다.")
        return self


class IndexedCriterion(StrictModel):
    """색인할 조건 하나.

    벡터는 text로 만들고 검색 결과는 criterion_id로 돌려준다.
    """

    criterion_id: int = Field(gt=0, strict=True)
    criterion_type: JobCriterionType
    text: str = Field(min_length=1, max_length=1000)


class IndexedPosting(StrictModel):
    """색인에 반영할 공고.

    ACTIVE가 아니거나 criteria가 비면 그 공고의 벡터를 지운다.
    """

    posting_id: int = Field(gt=0, strict=True)
    status: JobPostingStatus
    criteria: list[IndexedCriterion] = Field(default_factory=list)


class JobPostingIndexRequest(StrictModel):
    """Spring이 저장한 공고 조건을 색인에 반영하는 요청."""

    postings: list[IndexedPosting] = Field(min_length=1, max_length=200)

    @model_validator(mode="after")
    def reject_duplicate_ids(self) -> "JobPostingIndexRequest":
        posting_ids = [posting.posting_id for posting in self.postings]
        criterion_ids = [
            criterion.criterion_id for posting in self.postings for criterion in posting.criteria
        ]
        if len(set(posting_ids)) != len(posting_ids):
            raise ValueError("같은 posting_id를 한 요청에 두 번 보낼 수 없습니다.")
        if len(set(criterion_ids)) != len(criterion_ids):
            raise ValueError("같은 criterion_id를 한 요청에 두 번 보낼 수 없습니다.")
        return self


class JobPostingIndexResult(StrictModel):
    indexed_postings: int = Field(ge=0)
    indexed_criteria: int = Field(ge=0)
    deactivated_postings: int = Field(ge=0)


class JobPostingPruneRequest(StrictModel):
    """남길 공고 id 목록으로, 이 목록에 없는 공고의 벡터를 모두 지운다."""

    keep_posting_ids: list[Annotated[int, Field(gt=0, strict=True)]] = Field(
        min_length=1, max_length=10_000
    )

    @model_validator(mode="after")
    def reject_duplicate_ids(self) -> "JobPostingPruneRequest":
        if len(set(self.keep_posting_ids)) != len(self.keep_posting_ids):
            raise ValueError("같은 posting_id를 한 요청에 두 번 보낼 수 없습니다.")
        return self


class JobPostingPruneResult(StrictModel):
    kept_postings: int = Field(ge=0)
    removed_postings: int = Field(ge=0)


class SearchStatement(StrictModel):
    """Spring이 사용자 확정 여부를 검증해 보낸 STAR 문장."""

    statement_id: int = Field(gt=0, strict=True)
    body: str = Field(min_length=1, max_length=4000)


class MatchingRequest(StrictModel):
    """확정 STAR 문장과 가까운 조건을 찾는 요청."""

    statements: list[SearchStatement] = Field(min_length=1, max_length=200)

    @model_validator(mode="after")
    def reject_duplicate_statement_ids(self) -> "MatchingRequest":
        ids = [statement.statement_id for statement in self.statements]
        if len(set(ids)) != len(ids):
            raise ValueError("같은 statement_id를 한 요청에 두 번 보낼 수 없습니다.")
        return self


class CriterionMatch(StrictModel):
    criterion_id: int = Field(gt=0)
    statement_id: int = Field(gt=0)
    distance: float = Field(ge=0)


class MatchingResponse(StrictModel):
    matches: list[CriterionMatch] = Field(default_factory=list)
    collection_criteria: int = Field(ge=0)


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
    job_category: Optional[JobCategory] = None
    experience: Optional[JobExperience] = None

    @model_validator(mode="after")
    def check_outcome_fields(self) -> "JobPostingDetailExtractionResult":
        has_content = bool(self.criteria) or self.job_category or self.experience
        if self.outcome == ExtractionOutcome.UNCHANGED and has_content:
            raise ValueError("UNCHANGED 응답에는 조건·분류·경력을 다시 보내지 않습니다.")
        if self.outcome == ExtractionOutcome.UPDATED and (
            self.job_category is None or self.experience is None
        ):
            raise ValueError("UPDATED 응답에는 job_category와 experience가 필요합니다.")
        return self
