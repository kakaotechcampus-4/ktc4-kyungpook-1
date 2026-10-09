"""A-1 GitHub 저장소 활동 수집 요청·응답 모델.

OAuth 토큰은 DTO에 포함하지 않는다. Spring이 ``X-GitHub-Token`` 헤더로
전달하고 AI 서버는 한 요청을 처리하는 동안에만 메모리에서 사용한다.
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Annotated, Optional

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    PositiveInt,
    StringConstraints,
    model_validator,
)


CommitSha = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=7,
        max_length=40,
        pattern=r"^[0-9a-fA-F]+$",
    ),
]


class StrictModel(BaseModel):
    """알 수 없는 필드와 공백 문자열을 계약 단계에서 거절한다."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class FileChangeStatus(str, Enum):
    """GitHub 파일 변경 상태."""

    ADDED = "ADDED"
    MODIFIED = "MODIFIED"
    REMOVED = "REMOVED"
    RENAMED = "RENAMED"
    COPIED = "COPIED"
    CHANGED = "CHANGED"
    UNCHANGED = "UNCHANGED"
    UNKNOWN = "UNKNOWN"


class ExclusionReason(str, Enum):
    """본인 기여 후보에서 제외한 결정적 이유."""

    BOT = "BOT"
    MERGE_COMMIT = "MERGE_COMMIT"
    LOCKFILE_ONLY = "LOCKFILE_ONLY"
    NOT_OWN = "NOT_OWN"


class PartialReason(str, Enum):
    """수집 결과가 일부만 반환된 이유."""

    GITHUB_RATE_LIMITED = "GITHUB_RATE_LIMITED"
    CAP_EXCEEDED = "CAP_EXCEEDED"


class RepositoryTarget(StrictModel):
    """Spring이 소유권 검증을 마친 수집 대상 저장소."""

    github_repo_id: PositiveInt
    owner_login: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=100)
    default_branch: str = Field(min_length=1, max_length=255)


class CollectionActor(StrictModel):
    """본인 기여 판별에 사용하는 GitHub 사용자 정보."""

    github_login: str = Field(min_length=1, max_length=100)
    known_emails: list[str] = Field(default_factory=list)


class RepositoryCollectionRequest(StrictModel):
    """``POST /internal/collect`` 요청 본문."""

    user_repository_id: PositiveInt
    repository: RepositoryTarget
    actor: CollectionActor
    branches: list[str] = Field(
        default_factory=list,
        description="비어 있으면 repository.default_branch만 수집한다.",
    )
    known_commit_shas: list[CommitSha] = Field(
        default_factory=list,
        max_length=10_000,
        description="Spring이 이 연결 저장소에서 이미 수집한 commit SHA. AI는 상세 조회를 생략한다.",
    )

    @model_validator(mode="after")
    def normalize_branches(self) -> "RepositoryCollectionRequest":
        """브랜치 공백과 중복을 제거하고 빈 문자열을 거절한다."""
        normalized: list[str] = []
        for branch in self.branches:
            value = branch.strip()
            if not value:
                raise ValueError("branches에는 빈 브랜치를 넣을 수 없습니다.")
            if value not in normalized:
                normalized.append(value)
        self.branches = normalized
        unique_shas: list[str] = []
        seen: set[str] = set()
        for sha in self.known_commit_shas:
            key = sha.casefold()
            if key not in seen:
                seen.add(key)
                unique_shas.append(sha)
        self.known_commit_shas = unique_shas
        return self

    @property
    def collection_branches(self) -> list[str]:
        """실제로 GitHub에서 읽을 브랜치 목록."""
        return self.branches or [self.repository.default_branch]


class CandidateDetailRequest(StrictModel):
    """A가 선택한 후보의 상세 diff를 조회하는 요청."""

    user_repository_id: PositiveInt
    repository: RepositoryTarget
    commit_shas: list[CommitSha] = Field(
        default_factory=list,
        max_length=50,
        description="선택된 후보에 포함된 commit SHA. PR 전체면 빈 배열 가능",
    )
    github_pr_number: Optional[PositiveInt] = Field(
        None,
        description="PR 기반 후보의 GitHub PR 번호",
    )

    @model_validator(mode="after")
    def validate_candidate_reference(self) -> "CandidateDetailRequest":
        """commit SHA 또는 PR 번호 중 하나 이상을 요구한다."""
        unique_shas: list[str] = []
        seen: set[str] = set()
        for sha in self.commit_shas:
            key = sha.casefold()
            if key not in seen:
                seen.add(key)
                unique_shas.append(sha)
        self.commit_shas = unique_shas

        if not self.commit_shas and self.github_pr_number is None:
            raise ValueError("commit_shas 또는 github_pr_number가 필요합니다.")
        return self


class CollectedFileChange(StrictModel):
    """후보 그룹화에 필요한 변경 파일 메타데이터."""

    path: str = Field(min_length=1)
    status: FileChangeStatus
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)


class CandidateFileDiff(CollectedFileChange):
    """선택된 후보에서만 읽은 제한된 diff patch."""

    patch: Optional[str] = Field(
        None,
        description="GitHub이 제공한 diff patch. 바이너리·과대 파일은 null",
    )
    patch_truncated: bool = Field(
        False,
        description="개별 또는 전체 patch 상한으로 잘렸는지 여부",
    )


class CollectedCommit(StrictModel):
    """GitHub commit 상세 응답을 저장·분석 가능한 형태로 정규화한 값."""

    sha: str = Field(min_length=7, max_length=40)
    repository_id: PositiveInt
    author_login: Optional[str] = None
    author_name: Optional[str] = None
    author_email: Optional[str] = None
    message: str = Field(min_length=1)
    authored_at: datetime
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    changed_files: int = Field(ge=0)
    parent_count: int = Field(ge=0)
    files: list[CollectedFileChange] = Field(default_factory=list)
    pull_request_number: Optional[PositiveInt] = None
    issue_numbers: list[PositiveInt] = Field(default_factory=list)
    is_excluded: bool = False
    exclusion_reason: Optional[ExclusionReason] = None

    @model_validator(mode="after")
    def validate_exclusion(self) -> "CollectedCommit":
        """제외 여부와 이유가 항상 함께 움직이도록 한다."""
        if self.is_excluded != (self.exclusion_reason is not None):
            raise ValueError("is_excluded와 exclusion_reason은 함께 설정되어야 합니다.")
        return self


class CollectedReviewComment(StrictModel):
    """A가 리뷰 맥락을 판단하는 데 필요한 제한된 코드 리뷰 문맥."""

    comment_id: PositiveInt
    review_id: Optional[PositiveInt] = None
    pr_number: PositiveInt
    author_login: Optional[str] = None
    body_excerpt: str = Field(min_length=1)
    body_truncated: bool = False
    path: Optional[str] = None
    line: Optional[int] = Field(None, ge=1)
    created_at: datetime


class CollectedReview(StrictModel):
    """A가 후보와 STAR 근거를 분석할 PR review 문맥."""

    review_id: PositiveInt
    pr_number: PositiveInt
    state: str = Field(min_length=1)
    author_login: Optional[str] = None
    body_excerpt: Optional[str] = None
    body_truncated: bool = False
    submitted_at: Optional[datetime] = None
    comments: list[CollectedReviewComment] = Field(default_factory=list)


class CollectedPullRequest(StrictModel):
    """커밋과 리뷰가 연결된 PR 문맥."""

    number: PositiveInt
    title: str = Field(min_length=1)
    body_excerpt: Optional[str] = None
    body_truncated: bool = False
    state: str = Field(min_length=1)
    author_login: Optional[str] = None
    base_branch: str = Field(min_length=1)
    head_branch: str = Field(min_length=1)
    created_at: datetime
    merged_at: Optional[datetime] = None
    commit_shas: list[str] = Field(default_factory=list)
    linked_issue_numbers: list[PositiveInt] = Field(default_factory=list)
    reviews: list[CollectedReview] = Field(default_factory=list)


class CollectedIssue(StrictModel):
    """PR을 제외한 GitHub issue 문맥."""

    issue_number: PositiveInt
    title: str = Field(min_length=1)
    body_excerpt: Optional[str] = None
    body_truncated: bool = False
    state: str = Field(min_length=1)
    author_login: Optional[str] = None
    labels: list[str] = Field(default_factory=list)
    created_at: datetime
    closed_at: Optional[datetime] = None


class ContributionConfirmation(StrictModel):
    """로그인·이메일만으로 본인 여부를 확정하지 못한 커밋."""

    sha: str = Field(min_length=7, max_length=40)
    author_email: Optional[str] = None


class RepositoryCollectionResult(StrictModel):
    """Spring을 거쳐 A의 후보 그룹화·STAR 분석으로 전달할 수집 결과."""

    user_repository_id: PositiveInt
    head_sha: Optional[CommitSha] = Field(
        None,
        description="기본 브랜치(없으면 첫 수집 브랜치)에서 이번 수집이 확인한 최신 커밋 SHA.",
    )
    commits: list[CollectedCommit] = Field(default_factory=list)
    pull_requests: list[CollectedPullRequest] = Field(default_factory=list)
    issues: list[CollectedIssue] = Field(default_factory=list)
    needs_confirmation: list[ContributionConfirmation] = Field(default_factory=list)
    partial: bool = False
    partial_reason: Optional[PartialReason] = None

    @model_validator(mode="after")
    def validate_partial(self) -> "RepositoryCollectionResult":
        """부분 결과 여부와 사유가 항상 함께 움직이도록 한다."""
        if self.partial != (self.partial_reason is not None):
            raise ValueError("partial과 partial_reason은 함께 설정되어야 합니다.")
        return self


class CandidateCommitDetail(StrictModel):
    """A의 diff 해석에 필요한 선택 commit 상세."""

    sha: CommitSha
    message: str = Field(min_length=1)
    author_login: Optional[str] = None
    authored_at: datetime
    html_url: Optional[str] = None
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    files: list[CandidateFileDiff] = Field(default_factory=list)


class CandidateDetailResult(StrictModel):
    """A가 STAR 행동 근거를 해석할 선택 후보 상세."""

    user_repository_id: PositiveInt
    github_pr_number: Optional[PositiveInt] = None
    commits: list[CandidateCommitDetail] = Field(default_factory=list)
    partial: bool = False
    partial_reason: Optional[PartialReason] = None

    @model_validator(mode="after")
    def validate_partial(self) -> "CandidateDetailResult":
        """부분 결과 여부와 사유가 항상 함께 움직이도록 한다."""
        if self.partial != (self.partial_reason is not None):
            raise ValueError("partial과 partial_reason은 함께 설정되어야 합니다.")
        return self
