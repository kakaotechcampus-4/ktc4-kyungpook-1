"""커밋 선별·경험 그룹화·diff 분석·STAR 생성 모델."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    PositiveInt,
    StringConstraints,
    model_validator,
)

from schemas.collection import ExclusionReason, FileChangeStatus
from schemas.common import (
    AnalysisVerdict,
    CandidateSourceType,
    StarSlot,
    StarStatus,
    StatementConfidence,
)

#: Spring ``candidate.title``·``candidate.reason_text`` 컬럼 길이(VARCHAR(200)·VARCHAR(300)).
CANDIDATE_TITLE_MAX_LENGTH = 200
CANDIDATE_REASON_MAX_LENGTH = 300

NonBlankText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
ExperienceSourceType = Literal["PR", "COMMIT_CLUSTER"]


class StrictRequestModel(BaseModel):
    """알 수 없는 필드를 조용히 버리지 않는 내부 분석 API 입력 모델."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ChangedFile(StrictRequestModel):
    """커밋에 포함된 파일 변경 메타데이터."""

    path: str
    status: FileChangeStatus | None = None
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    patch: str | None = Field(
        None, description="상한을 넘거나 바이너리인 경우 본문 없이 전달"
    )


class CommitInput(StrictRequestModel):
    """Spring이 GitHub에서 수집해 전달하는 정규화 커밋."""

    sha: str = Field(..., min_length=7, max_length=40)
    repository_id: PositiveInt | None = None
    message: str
    author_login: str | None = None
    author_name: str | None = Field(
        None,
        description="GitHub 계정이 연결되지 않은 커밋의 작성자 이름",
    )
    author_email: str | None = None
    authored_at: datetime
    parent_count: int = Field(ge=0)
    additions: int | None = Field(
        None, ge=0, description="그룹화 단계에서는 diff를 조회하지 않으므로 비어 있을 수 있다"
    )
    deletions: int | None = Field(
        None, ge=0, description="그룹화 단계에서는 diff를 조회하지 않으므로 비어 있을 수 있다"
    )
    changed_files: int | None = Field(
        None, ge=0, description="변경 파일 수. files가 비어 있을 때 점수에 사용한다"
    )
    files: list[ChangedFile] = Field(default_factory=list)
    pull_request_number: PositiveInt | None = None
    issue_numbers: list[PositiveInt] = Field(default_factory=list)
    is_excluded: bool | None = Field(
        None,
        description="수집기가 판정한 제외 여부. null이면 셀렉터가 규칙으로 판정한다",
    )
    exclusion_reason: ExclusionReason | None = None

    @model_validator(mode="after")
    def validate_exclusion_decision(self) -> "CommitInput":
        """명시적 수집 판정은 제외 여부와 이유가 함께 움직여야 한다."""
        if self.is_excluded is None and self.exclusion_reason is not None:
            raise ValueError("exclusion_reason에는 is_excluded 판정이 필요합니다")
        if self.is_excluded is not None and self.is_excluded != (
            self.exclusion_reason is not None
        ):
            raise ValueError("is_excluded와 exclusion_reason은 함께 설정되어야 합니다")
        return self


class PullRequestContext(StrictRequestModel):
    """PR 후보의 제목·설명과 Issue 연결에 쓰는 PR 문맥(Spring ``pull_request`` 행)."""

    number: PositiveInt
    title: NonBlankText
    body_excerpt: str | None = None
    linked_issue_numbers: list[PositiveInt] = Field(
        default_factory=list, description="PR 본문의 Resolves #n 등으로 연결된 Issue"
    )


class IssueContext(StrictRequestModel):
    """Issue 후보의 제목·설명에 쓰는 Issue 문맥(Spring ``issue`` 행)."""

    number: PositiveInt
    title: NonBlankText
    body_excerpt: str | None = None


class ExperienceGroupingRequest(StrictRequestModel):
    """커밋 선별과 경험 그룹화를 요청한다."""

    repository_id: str
    target_login: NonBlankText
    commits: list[CommitInput]
    pull_requests: list[PullRequestContext] = Field(default_factory=list)
    issues: list[IssueContext] = Field(default_factory=list)
    collection_partial: bool = Field(
        False,
        description="수집 결과(RepositoryCollectionResult.partial)가 일부만 반환됐는지. "
        "true면 후보가 있어도 verdict는 PARTIAL",
    )


class ExperienceCandidate(BaseModel):
    """같은 기능을 위한 작업을 하나로 묶은 경험 후보.

    PR·Issue·커밋 단위로 나누지 않고, 같은 경험이면 여러 PR·Issue·커밋을 한 후보에 담는다.
    ``source_type``은 대표 출처(PR이 있으면 PR, 없으면 COMMIT_CLUSTER)다.
    """

    group_key: str
    source_type: ExperienceSourceType
    source_ref: str | None = None
    pull_request_number: PositiveInt | None = Field(
        None,
        description="대표 PR 번호(내 커밋이 가장 많은 PR). Spring candidate.github_pr_number",
    )
    pull_request_numbers: list[PositiveInt] = Field(
        default_factory=list, description="이 경험에 포함된 모든 PR 번호"
    )
    issue_numbers: list[PositiveInt] = Field(
        default_factory=list, description="이 경험에 연결된 모든 Issue 번호"
    )
    title: NonBlankText = Field(..., max_length=CANDIDATE_TITLE_MAX_LENGTH)
    reason: NonBlankText = Field(..., max_length=CANDIDATE_REASON_MAX_LENGTH)
    score: float = Field(ge=0.0, le=1.0)
    low_card_worth: bool = False
    commit_shas: list[str]


class ExperienceGroupingResponse(BaseModel):
    """사용자가 확정할 수 있는 경험 후보 목록."""

    verdict: AnalysisVerdict
    candidates: list[ExperienceCandidate] = Field(default_factory=list)
    excluded_commit_shas: list[str] = Field(default_factory=list)


class DiffEvidence(StrictRequestModel):
    """확정 경험에서 추출한 최소 diff 근거."""

    sha: str = Field(..., min_length=7, max_length=40)
    message: str
    author_login: str | None = None
    churn: str = Field(description="예: +71/-0")
    summary: str = Field(description="diff에서 직접 확인한 변경 요약")
    url: str | None = None


class DependencyFile(StrictRequestModel):
    """기술 스택 판별에 필요한 의존성·설정 파일."""

    path: str
    content: str


class StarAnalysisRequest(StrictRequestModel):
    """확정된 경험의 STAR 분석을 요청한다."""

    candidate_id: str
    target_login: str
    title: str
    source_type: CandidateSourceType
    source_ref: str | None = None
    evidence: list[DiffEvidence]
    dependency_files: list[DependencyFile] = Field(default_factory=list)
    confirmed_answers: list[str] = Field(default_factory=list)


class EvidenceReference(BaseModel):
    """STAR 문장에 연결된 커밋 근거."""

    sha: str = Field(..., min_length=7, max_length=40)
    message: str
    url: str | None = None


class StarFieldResult(BaseModel):
    """STAR 한 영역의 분석 결과."""

    text: str | None = None
    status: StarStatus
    confidence: StatementConfidence | None = None
    evidence: list[EvidenceReference] = Field(default_factory=list)
    insufficient_reason: str | None = None
    review_reason: str | None = None


class StarAnalysisResponse(BaseModel):
    """근거 검증을 마친 STAR 분석 결과."""

    title: str
    star: dict[StarSlot, StarFieldResult]
    missing_fields: list[StarSlot] = Field(default_factory=list)
    shared_with: str | None = None
    removed_claims: list[str] = Field(default_factory=list)
