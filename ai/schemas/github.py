"""Spring이 위임하는 GitHub 저장소 수집 요청/응답 모델."""

from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field

from schemas.analysis import ChangedFile

PartialReason = Literal[
    "LIMIT_EXCEEDED",
    "BRANCH_LIMIT_EXCEEDED",
    "GITHUB_RATE_LIMITED",
]


class GithubCollectionRequest(BaseModel):
    """선택된 저장소 하나의 수집 범위.

    OAuth 토큰은 본문에 넣지 않고 ``X-GitHub-Token`` 헤더로만 받는다.
    """

    owner: str = Field(..., min_length=1, max_length=39)
    repository: str = Field(..., min_length=1, max_length=100)
    target_login: str = Field(..., min_length=1, max_length=39)
    since: Optional[datetime] = None
    branches: list[str] = Field(default_factory=list, max_length=20)
    include_all_branches: bool = True
    max_commits: int = Field(1000, ge=1, le=1000)
    max_pull_requests: int = Field(50, ge=1, le=50)
    max_issues: int = Field(50, ge=1, le=50)


class GithubCommit(BaseModel):
    """GitHub 커밋 목록에서 정규화한 최소 메타데이터."""

    sha: str = Field(..., min_length=40, max_length=40)
    message: str
    author_login: Optional[str] = None
    author_name: Optional[str] = None
    authored_at: datetime
    parent_count: int = Field(ge=0)
    html_url: str
    branches: list[str] = Field(default_factory=list)
    pull_request_number: Optional[int] = Field(
        None, ge=1, description="이 커밋을 포함한 PR 번호(PR 연결 단계에서 채운다)"
    )
    issue_numbers: list[int] = Field(
        default_factory=list, description="커밋 메시지에서 추출한 Issue 번호"
    )


class GithubPullRequest(BaseModel):
    """후보 그룹화에 필요한 PR 메타데이터."""

    number: int = Field(ge=1)
    title: str
    state: Literal["open", "closed"]
    author_login: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    merged_at: Optional[datetime] = None
    base_ref: str
    head_ref: str
    html_url: str


class GithubIssue(BaseModel):
    """PR을 제외한 GitHub Issue 메타데이터."""

    number: int = Field(ge=1)
    title: str
    state: Literal["open", "closed"]
    author_login: Optional[str] = None
    labels: list[str] = Field(default_factory=list)
    created_at: datetime
    updated_at: datetime
    closed_at: Optional[datetime] = None
    html_url: str


class ReadCoverage(BaseModel):
    """이번 요청에서 실제로 읽은 GitHub 활동 수."""

    commits_read: int = Field(ge=0)
    pull_requests_read: int = Field(ge=0)
    issues_read: int = Field(ge=0)


class GithubCollectionResult(BaseModel):
    """DB 저장 없이 Spring으로 반환하는 정규화 수집 결과."""

    owner: str
    repository: str
    default_branch: str
    branches: list[str]
    commits: list[GithubCommit]
    pull_requests: list[GithubPullRequest]
    issues: list[GithubIssue]
    coverage: ReadCoverage
    partial: bool = False
    partial_reasons: list[PartialReason] = Field(default_factory=list)
    retry_after_sec: Optional[int] = None


#: 한 번에 diff를 조회할 수 있는 커밋 수. 확정 후보 하나의 커밋만 조회하는 용도다.
MAX_DIFF_SHAS = 30


class GithubCommitDiffRequest(BaseModel):
    """사용자가 확정한 후보의 커밋 diff 조회 범위.

    OAuth 토큰은 본문에 넣지 않고 ``X-GitHub-Token`` 헤더로만 받는다.
    """

    owner: str = Field(..., min_length=1, max_length=39)
    repository: str = Field(..., min_length=1, max_length=100)
    shas: list[str] = Field(..., min_length=1, max_length=MAX_DIFF_SHAS)


class GithubCommitDiff(BaseModel):
    """커밋 하나의 변경량과 파일별 patch."""

    sha: str = Field(..., min_length=40, max_length=40)
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    files: list[ChangedFile] = Field(default_factory=list)


class GithubCommitDiffResult(BaseModel):
    """요청한 SHA 목록의 diff 조회 결과."""

    owner: str
    repository: str
    commits: list[GithubCommitDiff] = Field(default_factory=list)
    missing_shas: list[str] = Field(
        default_factory=list, description="GitHub에서 찾지 못한 SHA"
    )
    partial: bool = False
    partial_reasons: list[PartialReason] = Field(default_factory=list)
    retry_after_sec: Optional[int] = None
