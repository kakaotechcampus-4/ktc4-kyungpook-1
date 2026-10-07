"""GitHub REST API 기반 A-1 저장소 활동 수집기.

토큰은 ``collect`` 호출 인자로만 받고 객체 필드나 결과에 저장하지 않는다.
DB 저장과 Job 상태 관리는 Spring의 책임이다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import PurePosixPath
from typing import Any, Optional, Union
from urllib.parse import quote

import httpx

from schemas.collection import (
    CandidateCommitDetail,
    CandidateDetailRequest,
    CandidateDetailResult,
    CandidateFileDiff,
    CollectedCommit,
    CollectedFileChange,
    CollectedIssue,
    CollectedPullRequest,
    CollectedReview,
    CollectedReviewComment,
    ContributionConfirmation,
    ExclusionReason,
    FileChangeStatus,
    PartialReason,
    RepositoryCollectionRequest,
    RepositoryCollectionResult,
)
from ports.repository_activity import RepositoryActivityExecution


GITHUB_API_VERSION = "2022-11-28"
GITHUB_ACCEPT = "application/vnd.github+json"
ISSUE_REFERENCE = re.compile(r"(?<![\w/])#(\d+)")
LOCKFILES = {
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "poetry.lock",
    "pdm.lock",
    "pipfile.lock",
    "cargo.lock",
    "composer.lock",
    "gemfile.lock",
    "gradle.lockfile",
}


class GithubApiError(RuntimeError):
    """GitHub API 호출 또는 응답 해석 실패."""

    def __init__(self, message: str, status_code: Optional[int] = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class GithubResourceNotFound(GithubApiError):
    """저장소가 없거나 현재 토큰으로 읽을 수 없음."""


class _RateLimited(RuntimeError):
    """호출자가 지금까지 모은 값을 부분 결과로 바꾸기 위한 내부 신호."""


@dataclass(frozen=True)
class CollectionLimits:
    """한 번의 수집이 GitHub와 AI 서버를 과도하게 점유하지 않도록 하는 상한."""

    max_commits: int = 1_000
    max_pull_requests: int = 50
    max_issues: int = 50
    max_text_chars: int = 1_000
    max_candidate_commits: int = 50
    max_patch_chars: int = 20_000
    max_candidate_patch_chars: int = 100_000


@dataclass
class _CollectionState:
    commits: list[CollectedCommit] = field(default_factory=list)
    pull_requests: list[CollectedPullRequest] = field(default_factory=list)
    issues: list[CollectedIssue] = field(default_factory=list)
    needs_confirmation: list[ContributionConfirmation] = field(default_factory=list)
    partial_reason: Optional[PartialReason] = None
    api_calls: int = 0
    head_sha: Optional[str] = None

    def mark_partial(self, reason: PartialReason) -> None:
        if self.partial_reason != PartialReason.GITHUB_RATE_LIMITED:
            self.partial_reason = reason


class GithubCollector:
    """선택 저장소의 commit·PR·review·issue·diff를 수집한다."""

    def __init__(
        self,
        *,
        base_url: str = "https://api.github.com",
        limits: CollectionLimits = CollectionLimits(),
        transport: Optional[httpx.AsyncBaseTransport] = None,
        timeout_seconds: float = 30.0,
    ) -> None:
        self._base_url = base_url
        self._limits = limits
        self._transport = transport
        self._timeout_seconds = timeout_seconds

    async def collect(
        self,
        request: RepositoryCollectionRequest,
        github_token: str,
    ) -> RepositoryActivityExecution[RepositoryCollectionResult]:
        """토큰을 요청 범위 HTTP 클라이언트에만 싣고 활동을 수집한다."""
        token = github_token.strip()
        if not token:
            raise ValueError("GitHub OAuth 토큰이 필요합니다.")

        state = _CollectionState()
        headers = {
            "Authorization": f"Bearer {token}",
            "Accept": GITHUB_ACCEPT,
            "X-GitHub-Api-Version": GITHUB_API_VERSION,
            "User-Agent": "Gitory/1.0",
        }

        async with httpx.AsyncClient(
            base_url=self._base_url,
            headers=headers,
            transport=self._transport,
            timeout=self._timeout_seconds,
        ) as client:
            try:
                await self._collect_pull_requests(client, request, state)
                await self._collect_commits(client, request, state)
                await self._collect_issues(client, request, state)
            except _RateLimited:
                state.mark_partial(PartialReason.GITHUB_RATE_LIMITED)

        result = RepositoryCollectionResult(
            user_repository_id=request.user_repository_id,
            head_sha=state.head_sha,
            commits=state.commits,
            pull_requests=state.pull_requests,
            issues=state.issues,
            needs_confirmation=state.needs_confirmation,
            partial=state.partial_reason is not None,
            partial_reason=state.partial_reason,
        )
        return RepositoryActivityExecution(result=result, api_calls=state.api_calls)

    async def fetch_candidate_details(
        self,
        request: CandidateDetailRequest,
        github_token: str,
    ) -> RepositoryActivityExecution[CandidateDetailResult]:
        """A가 선택한 SHA 또는 PR의 diff만 상한을 두고 조회한다."""
        token = github_token.strip()
        if not token:
            raise ValueError("GitHub OAuth 토큰이 필요합니다.")

        state = _CollectionState()
        headers = {
            "Authorization": f"Bearer {token}",
            "Accept": GITHUB_ACCEPT,
            "X-GitHub-Api-Version": GITHUB_API_VERSION,
            "User-Agent": "Gitory/1.0",
        }
        details: list[CandidateCommitDetail] = []
        remaining_patch_chars = self._limits.max_candidate_patch_chars

        async with httpx.AsyncClient(
            base_url=self._base_url,
            headers=headers,
            transport=self._transport,
            timeout=self._timeout_seconds,
        ) as client:
            try:
                shas = list(request.commit_shas)
                if not shas and request.github_pr_number is not None:
                    shas = await self._collect_candidate_pr_shas(
                        client,
                        request,
                        state,
                    )

                if len(shas) > self._limits.max_candidate_commits:
                    state.mark_partial(PartialReason.CAP_EXCEEDED)
                    shas = shas[: self._limits.max_candidate_commits]

                for sha in shas:
                    detail, files = await self._collect_commit_detail(
                        client,
                        request,
                        state,
                        sha,
                    )
                    normalized, used_chars, was_truncated = (
                        self._to_candidate_commit_detail(
                            detail,
                            files,
                            remaining_patch_chars,
                        )
                    )
                    details.append(normalized)
                    remaining_patch_chars = max(
                        remaining_patch_chars - used_chars,
                        0,
                    )
                    if was_truncated:
                        state.mark_partial(PartialReason.CAP_EXCEEDED)
            except _RateLimited:
                state.mark_partial(PartialReason.GITHUB_RATE_LIMITED)

        result = CandidateDetailResult(
            user_repository_id=request.user_repository_id,
            github_pr_number=request.github_pr_number,
            commits=details,
            partial=state.partial_reason is not None,
            partial_reason=state.partial_reason,
        )
        return RepositoryActivityExecution(result=result, api_calls=state.api_calls)

    async def _collect_pull_requests(
        self,
        client: httpx.AsyncClient,
        request: RepositoryCollectionRequest,
        state: _CollectionState,
    ) -> None:
        repository = request.repository
        pulls = await self._get_list(
            client,
            state,
            f"/repos/{repository.owner_login}/{repository.name}/pulls",
            params={
                "state": "all",
                "sort": "updated",
                "direction": "desc",
                "per_page": min(self._limits.max_pull_requests + 1, 100),
            },
        )
        if len(pulls) > self._limits.max_pull_requests:
            state.mark_partial(PartialReason.CAP_EXCEEDED)

        for pull in pulls[: self._limits.max_pull_requests]:
            number = _required_int(pull, "number")
            commit_shas = await self._collect_pull_commit_shas(
                client, request, state, number
            )
            review_comments = await self._collect_review_comments(
                client, request, state, number
            )
            reviews = await self._collect_reviews(
                client, request, state, number, review_comments
            )
            title = _required_text(pull, "title")
            body = _optional_text(pull, "body")
            body_excerpt, body_truncated = _excerpt(
                body, self._limits.max_text_chars
            )
            state.pull_requests.append(
                CollectedPullRequest(
                    number=number,
                    title=title,
                    body_excerpt=body_excerpt,
                    body_truncated=body_truncated,
                    state=_required_text(pull, "state").upper(),
                    author_login=_nested_optional_text(pull, "user", "login"),
                    base_branch=_nested_required_text(pull, "base", "ref"),
                    head_branch=_nested_required_text(pull, "head", "ref"),
                    created_at=_required_text(pull, "created_at"),
                    merged_at=_optional_text(pull, "merged_at"),
                    commit_shas=commit_shas,
                    linked_issue_numbers=_issue_numbers(f"{title}\n{body or ''}"),
                    reviews=reviews,
                )
            )

    async def _collect_pull_commit_shas(
        self,
        client: httpx.AsyncClient,
        request: RepositoryCollectionRequest,
        state: _CollectionState,
        number: int,
    ) -> list[str]:
        repository = request.repository
        shas: list[str] = []
        page = 1
        while len(shas) <= self._limits.max_commits:
            items = await self._get_list(
                client,
                state,
                f"/repos/{repository.owner_login}/{repository.name}/pulls/{number}/commits",
                params={"per_page": 100, "page": page},
            )
            shas.extend(_required_text(item, "sha") for item in items)
            if len(shas) > self._limits.max_commits:
                state.mark_partial(PartialReason.CAP_EXCEEDED)
                return shas[: self._limits.max_commits]
            if len(items) < 100:
                break
            page += 1
        return shas

    async def _collect_candidate_pr_shas(
        self,
        client: httpx.AsyncClient,
        request: CandidateDetailRequest,
        state: _CollectionState,
    ) -> list[str]:
        """PR 기반 후보의 commit SHA를 상세 조회 상한까지 가져온다."""
        if request.github_pr_number is None:
            return []

        repository = request.repository
        shas: list[str] = []
        page = 1
        while len(shas) <= self._limits.max_candidate_commits:
            items = await self._get_list(
                client,
                state,
                (
                    f"/repos/{repository.owner_login}/{repository.name}/pulls/"
                    f"{request.github_pr_number}/commits"
                ),
                params={"per_page": 100, "page": page},
            )
            shas.extend(_required_text(item, "sha") for item in items)
            if len(shas) > self._limits.max_candidate_commits:
                state.mark_partial(PartialReason.CAP_EXCEEDED)
                return shas[: self._limits.max_candidate_commits]
            if len(items) < 100:
                break
            page += 1
        return shas

    async def _collect_reviews(
        self,
        client: httpx.AsyncClient,
        request: RepositoryCollectionRequest,
        state: _CollectionState,
        number: int,
        review_comments: list[CollectedReviewComment],
    ) -> list[CollectedReview]:
        repository = request.repository
        reviews: list[CollectedReview] = []
        page = 1
        while True:
            items = await self._get_list(
                client,
                state,
                f"/repos/{repository.owner_login}/{repository.name}/pulls/{number}/reviews",
                params={"per_page": 100, "page": page},
            )
            for review in items:
                review_id = _required_int(review, "id")
                body = _optional_text(review, "body")
                body_excerpt, body_truncated = _excerpt(
                    body, self._limits.max_text_chars
                )
                comments = [
                    comment
                    for comment in review_comments
                    if comment.review_id == review_id
                ]
                reviews.append(
                    CollectedReview(
                        review_id=review_id,
                        pr_number=number,
                        state=_required_text(review, "state").upper(),
                        author_login=_nested_optional_text(review, "user", "login"),
                        body_excerpt=body_excerpt,
                        body_truncated=body_truncated,
                        submitted_at=_optional_text(review, "submitted_at"),
                        comments=comments,
                    )
                )
            if len(items) < 100:
                break
            page += 1
        return reviews

    async def _collect_review_comments(
        self,
        client: httpx.AsyncClient,
        request: RepositoryCollectionRequest,
        state: _CollectionState,
        number: int,
    ) -> list[CollectedReviewComment]:
        """PR review 상태 API와 별도로 제공되는 코드 라인 코멘트를 수집한다."""
        repository = request.repository
        comments: list[CollectedReviewComment] = []
        page = 1
        while True:
            items = await self._get_list(
                client,
                state,
                f"/repos/{repository.owner_login}/{repository.name}/pulls/{number}/comments",
                params={"per_page": 100, "page": page},
            )
            for comment in items:
                body_excerpt, body_truncated = _excerpt(
                    _required_text(comment, "body"),
                    self._limits.max_text_chars,
                )
                comments.append(
                    CollectedReviewComment(
                        comment_id=_required_int(comment, "id"),
                        review_id=_optional_int(comment, "pull_request_review_id"),
                        pr_number=number,
                        author_login=_nested_optional_text(comment, "user", "login"),
                        body_excerpt=body_excerpt or "내용 없음",
                        body_truncated=body_truncated,
                        path=_optional_text(comment, "path"),
                        line=_optional_int(comment, "line"),
                        created_at=_required_text(comment, "created_at"),
                    )
                )
            if len(items) < 100:
                break
            page += 1
        return comments

    async def _collect_commits(
        self,
        client: httpx.AsyncClient,
        request: RepositoryCollectionRequest,
        state: _CollectionState,
    ) -> None:
        repository = request.repository
        summaries_by_sha: dict[str, dict[str, Any]] = {}

        # ``since`` 이후 새 커밋이 하나도 없어도 현재 기본 브랜치의 head는
        # Spring이 다음 수집 기준과 저장소 상태를 유지하는 데 필요하다.
        # 목록 응답의 첫 항목에 기대면 빈 증분 응답에서 head_sha가 사라지고,
        # 기본 브랜치가 수집 목록에 없을 때 다른 브랜치 SHA를 잘못 기록한다.
        branch = quote(repository.default_branch, safe="")
        branch_info = await self._get_dict(
            client,
            state,
            f"/repos/{repository.owner_login}/{repository.name}/branches/{branch}",
            params={},
        )
        state.head_sha = _nested_required_text(branch_info, "commit", "sha")

        for branch in request.collection_branches:
            page = 1
            while len(summaries_by_sha) <= self._limits.max_commits:
                params: dict[str, Any] = {"sha": branch, "per_page": 100, "page": page}
                if request.since is not None:
                    params["since"] = request.since.isoformat().replace("+00:00", "Z")
                items = await self._get_list(
                    client,
                    state,
                    f"/repos/{repository.owner_login}/{repository.name}/commits",
                    params=params,
                )
                for item in items:
                    summaries_by_sha.setdefault(_required_text(item, "sha"), item)
                    if len(summaries_by_sha) > self._limits.max_commits:
                        state.mark_partial(PartialReason.CAP_EXCEEDED)
                        break
                if len(items) < 100 or len(summaries_by_sha) > self._limits.max_commits:
                    break
                page += 1

        pull_by_sha = _primary_pull_by_sha(state.pull_requests)
        selected_shas = list(summaries_by_sha)[: self._limits.max_commits]
        for sha in selected_shas:
            detail, files = await self._collect_commit_detail(
                client, request, state, sha
            )
            commit, confirmation = self._to_commit(
                request,
                detail,
                files,
                pull_by_sha.get(sha),
            )
            state.commits.append(commit)
            if confirmation is not None:
                state.needs_confirmation.append(confirmation)

    async def _collect_commit_detail(
        self,
        client: httpx.AsyncClient,
        request: Union[RepositoryCollectionRequest, CandidateDetailRequest],
        state: _CollectionState,
        sha: str,
    ) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        repository = request.repository
        path = f"/repos/{repository.owner_login}/{repository.name}/commits/{sha}"
        detail = await self._get_dict(
            client, state, path, params={"per_page": 100, "page": 1}
        )
        files = list(_optional_list(detail, "files"))
        page = 2
        last_page_size = len(files)
        while last_page_size == 100:
            next_detail = await self._get_dict(
                client, state, path, params={"per_page": 100, "page": page}
            )
            next_files = _optional_list(next_detail, "files")
            files.extend(next_files)
            last_page_size = len(next_files)
            page += 1
        return detail, files

    def _to_candidate_commit_detail(
        self,
        detail: dict[str, Any],
        file_nodes: list[dict[str, Any]],
        remaining_patch_chars: int,
    ) -> tuple[CandidateCommitDetail, int, bool]:
        """GitHub commit 응답을 A용 제한 diff 계약으로 바꾼다."""
        commit_node = _required_dict(detail, "commit")
        commit_author = _required_dict(commit_node, "author")
        stats = _optional_dict(detail, "stats")
        normalized_files: list[CandidateFileDiff] = []
        used_chars = 0
        was_truncated = False

        for node in file_nodes:
            raw_status = (_optional_text(node, "status") or "UNKNOWN").upper()
            try:
                status = FileChangeStatus(raw_status)
            except ValueError:
                status = FileChangeStatus.UNKNOWN

            raw_patch = _optional_text(node, "patch")
            patch: Optional[str] = None
            patch_truncated = False
            if raw_patch:
                total_remaining = max(remaining_patch_chars - used_chars, 0)
                allowed = min(self._limits.max_patch_chars, total_remaining)
                if allowed > 0:
                    patch = raw_patch[:allowed]
                    used_chars += len(patch)
                if len(raw_patch) > allowed:
                    patch_truncated = True
                    was_truncated = True

            normalized_files.append(
                CandidateFileDiff(
                    path=_required_text(node, "filename"),
                    status=status,
                    additions=int(node.get("additions", 0)),
                    deletions=int(node.get("deletions", 0)),
                    patch=patch,
                    patch_truncated=patch_truncated,
                )
            )

        return (
            CandidateCommitDetail(
                sha=_required_text(detail, "sha"),
                message=_required_text(commit_node, "message"),
                author_login=_nested_optional_text(detail, "author", "login"),
                authored_at=_required_text(commit_author, "date"),
                html_url=_optional_text(detail, "html_url"),
                additions=int(stats.get("additions", 0)),
                deletions=int(stats.get("deletions", 0)),
                files=normalized_files,
            ),
            used_chars,
            was_truncated,
        )

    def _to_commit(
        self,
        request: RepositoryCollectionRequest,
        detail: dict[str, Any],
        file_nodes: list[dict[str, Any]],
        pull_request_number: Optional[int],
    ) -> tuple[CollectedCommit, Optional[ContributionConfirmation]]:
        commit_node = _required_dict(detail, "commit")
        author_node = _optional_dict(detail, "author")
        commit_author = _required_dict(commit_node, "author")
        stats = _optional_dict(detail, "stats")

        files = [self._to_file_change(file_node) for file_node in file_nodes]
        login = _optional_text(author_node, "login")
        author_type = _optional_text(author_node, "type")
        author_name = _optional_text(commit_author, "name")
        author_email = _optional_text(commit_author, "email")
        parent_count = len(_optional_list(detail, "parents"))
        message = _required_text(commit_node, "message")

        exclusion_reason, confirmation = _contribution_decision(
            sha=_required_text(detail, "sha"),
            login=login,
            author_type=author_type,
            author_name=author_name,
            author_email=author_email,
            parent_count=parent_count,
            files=files,
            request=request,
        )

        collected = CollectedCommit(
            sha=_required_text(detail, "sha"),
            repository_id=request.repository.github_repo_id,
            author_login=login,
            author_name=author_name,
            author_email=author_email,
            message=message,
            authored_at=_required_text(commit_author, "date"),
            additions=int(stats.get("additions", 0)),
            deletions=int(stats.get("deletions", 0)),
            changed_files=len(files),
            parent_count=parent_count,
            files=files,
            pull_request_number=pull_request_number,
            issue_numbers=_issue_numbers(message),
            is_excluded=exclusion_reason is not None,
            exclusion_reason=exclusion_reason,
        )
        return collected, confirmation

    def _to_file_change(self, node: dict[str, Any]) -> CollectedFileChange:
        raw_status = (_optional_text(node, "status") or "UNKNOWN").upper()
        try:
            status = FileChangeStatus(raw_status)
        except ValueError:
            status = FileChangeStatus.UNKNOWN
        return CollectedFileChange(
            path=_required_text(node, "filename"),
            status=status,
            additions=int(node.get("additions", 0)),
            deletions=int(node.get("deletions", 0)),
        )

    async def _collect_issues(
        self,
        client: httpx.AsyncClient,
        request: RepositoryCollectionRequest,
        state: _CollectionState,
    ) -> None:
        repository = request.repository
        page = 1
        done = False
        while not done and len(state.issues) <= self._limits.max_issues:
            items = await self._get_list(
                client,
                state,
                f"/repos/{repository.owner_login}/{repository.name}/issues",
                params={
                    "state": "all",
                    "sort": "updated",
                    "direction": "desc",
                    "per_page": 100,
                    "page": page,
                },
            )
            for issue in items:
                if "pull_request" in issue:
                    continue
                if len(state.issues) >= self._limits.max_issues:
                    state.mark_partial(PartialReason.CAP_EXCEEDED)
                    done = True
                    break
                labels = [
                    _required_text(label, "name")
                    for label in _optional_list(issue, "labels")
                ]
                body_excerpt, body_truncated = _excerpt(
                    _optional_text(issue, "body"),
                    self._limits.max_text_chars,
                )
                state.issues.append(
                    CollectedIssue(
                        issue_number=_required_int(issue, "number"),
                        title=_required_text(issue, "title"),
                        body_excerpt=body_excerpt,
                        body_truncated=body_truncated,
                        state=_required_text(issue, "state").upper(),
                        author_login=_nested_optional_text(issue, "user", "login"),
                        labels=labels,
                        created_at=_required_text(issue, "created_at"),
                        closed_at=_optional_text(issue, "closed_at"),
                    )
                )
            if len(items) < 100:
                done = True
            page += 1

    async def _get_list(
        self,
        client: httpx.AsyncClient,
        state: _CollectionState,
        path: str,
        *,
        params: dict[str, Any],
    ) -> list[dict[str, Any]]:
        value = await self._get_json(client, state, path, params=params)
        if not isinstance(value, list):
            raise GithubApiError("GitHub 목록 응답이 배열이 아닙니다.")
        return [_ensure_dict(item) for item in value]

    async def _get_dict(
        self,
        client: httpx.AsyncClient,
        state: _CollectionState,
        path: str,
        *,
        params: dict[str, Any],
    ) -> dict[str, Any]:
        value = await self._get_json(client, state, path, params=params)
        return _ensure_dict(value)

    async def _get_json(
        self,
        client: httpx.AsyncClient,
        state: _CollectionState,
        path: str,
        *,
        params: dict[str, Any],
    ) -> Any:
        state.api_calls += 1
        try:
            response = await client.get(path, params=params)
            response.raise_for_status()
            return response.json()
        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code
            if status == 429 or (
                status == 403
                and exc.response.headers.get("X-RateLimit-Remaining") == "0"
            ):
                raise _RateLimited() from exc
            if status == 404:
                raise GithubResourceNotFound(
                    "저장소가 없거나 현재 권한으로 읽을 수 없습니다.", status
                ) from exc
            raise GithubApiError(
                f"GitHub API 요청에 실패했습니다. status={status}", status
            ) from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise GithubApiError("GitHub API 응답을 처리하지 못했습니다.") from exc


def _contribution_decision(
    *,
    sha: str,
    login: Optional[str],
    author_type: Optional[str],
    author_name: Optional[str],
    author_email: Optional[str],
    parent_count: int,
    files: list[CollectedFileChange],
    request: RepositoryCollectionRequest,
) -> tuple[Optional[ExclusionReason], Optional[ContributionConfirmation]]:
    """결정적 제외 규칙을 적용하고 애매한 저자는 확인 대상으로 남긴다."""
    normalized_login = (login or "").lower()
    if (author_type or "").lower() == "bot" or normalized_login.endswith("[bot]"):
        return ExclusionReason.BOT, None
    if parent_count >= 2:
        return ExclusionReason.MERGE_COMMIT, None
    if files and all(PurePosixPath(item.path).name.lower() in LOCKFILES for item in files):
        return ExclusionReason.LOCKFILE_ONLY, None

    actor_login = request.actor.github_login.lower()
    known_emails = {email.lower() for email in request.actor.known_emails}
    if normalized_login == actor_login:
        return None, None
    if author_email and author_email.lower() in known_emails:
        return None, None

    comparable_name = _identity_text(author_name or "")
    comparable_login = _identity_text(request.actor.github_login)
    if comparable_name and SequenceMatcher(None, comparable_name, comparable_login).ratio() >= 0.6:
        return (
            ExclusionReason.NOT_OWN,
            ContributionConfirmation(sha=sha, author_email=author_email),
        )
    return ExclusionReason.NOT_OWN, None


def _primary_pull_by_sha(pulls: list[CollectedPullRequest]) -> dict[str, int]:
    """커밋마다 대표 PR 하나를 고른다. 여러 PR에 속하면 커밋 수가 가장 적은 PR을 쓴다.

    ``develop → main`` 같은 릴리스 PR은 기능 PR의 커밋을 모두 다시 담는다. 수집 순서
    (최근 갱신순)에 맡기면 릴리스 PR이 기능 PR을 덮어 모든 커밋이 한 후보로 합쳐질 수
    있으므로, 가장 좁은 범위의 PR을 고르고 같으면 번호가 작은 PR을 고른다.
    """
    primary: dict[str, int] = {}
    for pull in sorted(pulls, key=lambda item: (len(item.commit_shas), item.number)):
        for sha in pull.commit_shas:
            primary.setdefault(sha, pull.number)
    return primary


def _identity_text(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def _issue_numbers(text: str) -> list[int]:
    """등장 순서를 유지하면서 중복 issue 번호를 제거한다."""
    return list(dict.fromkeys(int(value) for value in ISSUE_REFERENCE.findall(text)))


def _excerpt(
    text: Optional[str], max_chars: int = 1_000
) -> tuple[Optional[str], bool]:
    """원문 저장으로 오해되지 않도록 길이가 제한된 excerpt와 절단 여부를 반환한다."""
    if text is None:
        return None, False
    normalized = " ".join(text.split())
    if not normalized:
        return None, False
    return normalized[:max_chars], len(normalized) > max_chars


def _ensure_dict(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise GithubApiError("GitHub 객체 응답 형식이 올바르지 않습니다.")
    return value


def _required_dict(node: dict[str, Any], field: str) -> dict[str, Any]:
    value = node.get(field)
    if not isinstance(value, dict):
        raise GithubApiError(f"GitHub 응답에 {field} 객체가 없습니다.")
    return value


def _optional_dict(node: dict[str, Any], field: str) -> dict[str, Any]:
    value = node.get(field)
    return value if isinstance(value, dict) else {}


def _optional_list(node: dict[str, Any], field: str) -> list[dict[str, Any]]:
    value = node.get(field)
    if value is None:
        return []
    if not isinstance(value, list):
        raise GithubApiError(f"GitHub 응답의 {field}가 배열이 아닙니다.")
    return [_ensure_dict(item) for item in value]


def _required_text(node: dict[str, Any], field: str) -> str:
    value = node.get(field)
    if not isinstance(value, str) or not value.strip():
        raise GithubApiError(f"GitHub 응답에 {field}가 없습니다.")
    return value


def _optional_text(node: dict[str, Any], field: str) -> Optional[str]:
    value = node.get(field)
    return value if isinstance(value, str) else None


def _required_int(node: dict[str, Any], field: str) -> int:
    value = node.get(field)
    if not isinstance(value, int) or isinstance(value, bool):
        raise GithubApiError(f"GitHub 응답에 {field} 정수가 없습니다.")
    return value


def _optional_int(node: dict[str, Any], field: str) -> Optional[int]:
    value = node.get(field)
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool):
        raise GithubApiError(f"GitHub 응답의 {field}가 정수가 아닙니다.")
    return value


def _nested_optional_text(
    node: dict[str, Any], parent: str, field: str
) -> Optional[str]:
    parent_node = node.get(parent)
    return _optional_text(parent_node, field) if isinstance(parent_node, dict) else None


def _nested_required_text(node: dict[str, Any], parent: str, field: str) -> str:
    parent_node = _required_dict(node, parent)
    return _required_text(parent_node, field)
