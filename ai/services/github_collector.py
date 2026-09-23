"""요청 수명 동안만 OAuth 토큰을 사용해 GitHub 활동을 수집한다."""

from __future__ import annotations

import time
from collections.abc import Mapping
from typing import Any, Optional

import httpx

from schemas.github import (
    GithubCollectionRequest,
    GithubCollectionResult,
    GithubCommit,
    GithubIssue,
    GithubPullRequest,
    ReadCoverage,
)

GITHUB_API_URL = "https://api.github.com"
GITHUB_API_VERSION = "2022-11-28"
MAX_BRANCHES = 20


class GithubCollectionError(RuntimeError):
    """GitHub 호출 자체가 실패해 수집 결과를 만들 수 없는 경우."""

    code = "GITHUB_API_ERROR"
    retryable = True
    public_message = "GitHub 저장소 활동을 수집하지 못했습니다."


class GithubAuthFailed(GithubCollectionError):
    """토큰이 만료·폐기되어 GitHub가 401을 반환한 경우. 재연동이 필요하다."""

    code = "GITHUB_AUTH_FAILED"
    retryable = False
    public_message = "GitHub 인증이 만료되었거나 유효하지 않습니다. GitHub 연동을 다시 진행해 주세요."


class GithubPermissionDenied(GithubCollectionError):
    """토큰은 유효하지만 권한·조직 SSO 승인이 없어 GitHub가 403을 반환한 경우."""

    code = "GITHUB_PERMISSION_DENIED"
    retryable = False
    public_message = "이 저장소에 접근할 GitHub 권한이 없습니다."


class GithubRepoNotFound(GithubCollectionError):
    """저장소가 없거나, 비공개 저장소에 접근 권한이 없어 GitHub가 404를 반환한 경우."""

    code = "GITHUB_REPO_NOT_FOUND"
    retryable = False
    public_message = "저장소를 찾을 수 없거나 접근 권한이 없습니다."


class GithubRateLimited(GithubCollectionError):
    """GitHub API 요청 한도에 도달한 경우."""

    def __init__(self, retry_after_sec: Optional[int]) -> None:
        super().__init__("GitHub API 요청 한도에 도달했습니다.")
        self.retry_after_sec = retry_after_sec


class GithubCollector:
    """GitHub REST API에서 커밋·PR·Issue를 읽는 무상태 수집기.

    토큰은 ``collect``의 지역 변수와 요청 전용 ``AsyncClient`` 헤더에만 존재한다.
    수집기는 토큰, 응답 본문 또는 사용자 데이터를 인스턴스에 보관하지 않는다.
    """

    def __init__(
        self,
        *,
        transport: Optional[httpx.AsyncBaseTransport] = None,
        timeout_seconds: float = 20.0,
    ) -> None:
        self._transport = transport
        self._timeout_seconds = timeout_seconds

    async def collect(
        self, request: GithubCollectionRequest, github_token: str
    ) -> GithubCollectionResult:
        """저장소 활동을 정규화해 반환하고 토큰을 보관하지 않는다."""
        headers = {
            "Authorization": f"Bearer {github_token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": GITHUB_API_VERSION,
            "User-Agent": "gitory-ai-collector",
        }
        async with httpx.AsyncClient(
            base_url=GITHUB_API_URL,
            headers=headers,
            timeout=self._timeout_seconds,
            transport=self._transport,
        ) as client:
            return await self._collect_with_client(client, request)

    async def _collect_with_client(
        self, client: httpx.AsyncClient, request: GithubCollectionRequest
    ) -> GithubCollectionResult:
        repo_path = f"/repos/{request.owner}/{request.repository}"
        repository = await self._get_json(client, repo_path)
        default_branch = str(repository["default_branch"])

        partial_reasons: list[str] = []
        retry_after_sec: Optional[int] = None
        branches = await self._resolve_branches(
            client, repo_path, request, default_branch, partial_reasons
        )

        commits: list[GithubCommit] = []
        pulls: list[GithubPullRequest] = []
        issues: list[GithubIssue] = []

        try:
            commits, commits_truncated = await self._collect_commits(
                client, repo_path, request, branches
            )
            if commits_truncated:
                partial_reasons.append("LIMIT_EXCEEDED")

            pull_rows, pulls_truncated = await self._paginate(
                client,
                f"{repo_path}/pulls",
                {"state": "all", "sort": "updated", "direction": "desc"},
                request.max_pull_requests,
            )
            pulls = [self._normalize_pull(row) for row in pull_rows]
            if pulls_truncated:
                partial_reasons.append("LIMIT_EXCEEDED")

            # GitHub의 issues API에는 PR도 섞여 있으므로 넉넉히 읽고 PR 행을 제외한다.
            issue_rows, issues_truncated = await self._paginate(
                client,
                f"{repo_path}/issues",
                {"state": "all", "sort": "updated", "direction": "desc"},
                request.max_issues + request.max_pull_requests,
            )
            issues = [
                self._normalize_issue(row)
                for row in issue_rows
                if "pull_request" not in row
            ][: request.max_issues]
            if issues_truncated or len(
                [row for row in issue_rows if "pull_request" not in row]
            ) > request.max_issues:
                partial_reasons.append("LIMIT_EXCEEDED")
        except GithubRateLimited as exc:
            partial_reasons.append("GITHUB_RATE_LIMITED")
            retry_after_sec = exc.retry_after_sec

        unique_reasons = list(dict.fromkeys(partial_reasons))
        return GithubCollectionResult(
            owner=request.owner,
            repository=request.repository,
            default_branch=default_branch,
            branches=branches,
            commits=commits,
            pull_requests=pulls,
            issues=issues,
            coverage=ReadCoverage(
                commits_read=len(commits),
                pull_requests_read=len(pulls),
                issues_read=len(issues),
            ),
            partial=bool(unique_reasons),
            partial_reasons=unique_reasons,
            retry_after_sec=retry_after_sec,
        )

    async def _resolve_branches(
        self,
        client: httpx.AsyncClient,
        repo_path: str,
        request: GithubCollectionRequest,
        default_branch: str,
        partial_reasons: list[str],
    ) -> list[str]:
        if request.branches:
            return list(dict.fromkeys(request.branches))
        if not request.include_all_branches:
            return [default_branch]

        rows, truncated = await self._paginate(
            client, f"{repo_path}/branches", {}, MAX_BRANCHES
        )
        branches = [str(row["name"]) for row in rows]
        if truncated:
            partial_reasons.append("BRANCH_LIMIT_EXCEEDED")
        return branches or [default_branch]

    async def _collect_commits(
        self,
        client: httpx.AsyncClient,
        repo_path: str,
        request: GithubCollectionRequest,
        branches: list[str],
    ) -> tuple[list[GithubCommit], bool]:
        commits_by_sha: dict[str, GithubCommit] = {}
        truncated = False

        for branch in branches:
            remaining = request.max_commits - len(commits_by_sha)
            if remaining <= 0:
                truncated = True
                break
            params: dict[str, Any] = {"sha": branch}
            if request.since is not None:
                params["since"] = request.since.isoformat()
            rows, branch_truncated = await self._paginate(
                client, f"{repo_path}/commits", params, remaining
            )
            truncated = truncated or branch_truncated
            for row in rows:
                sha = str(row["sha"])
                if sha in commits_by_sha:
                    existing = commits_by_sha[sha]
                    if branch not in existing.branches:
                        existing.branches.append(branch)
                    continue
                commit = self._normalize_commit(row, branch)
                commits_by_sha[sha] = commit

        return list(commits_by_sha.values()), truncated

    async def _paginate(
        self,
        client: httpx.AsyncClient,
        path: str,
        params: Mapping[str, Any],
        limit: int,
    ) -> tuple[list[dict[str, Any]], bool]:
        rows: list[dict[str, Any]] = []
        page = 1
        has_more = False

        while len(rows) < limit:
            page_size = min(100, limit - len(rows))
            response = await self._request(
                client,
                path,
                params={**params, "per_page": page_size, "page": page},
            )
            payload = response.json()
            if not isinstance(payload, list):
                raise GithubCollectionError("GitHub 목록 응답 형식이 올바르지 않습니다.")
            rows.extend(payload)
            has_more = "next" in response.links
            if not has_more or not payload:
                break
            page += 1

        return rows[:limit], has_more

    async def _get_json(
        self, client: httpx.AsyncClient, path: str
    ) -> dict[str, Any]:
        response = await self._request(client, path)
        payload = response.json()
        if not isinstance(payload, dict):
            raise GithubCollectionError("GitHub 객체 응답 형식이 올바르지 않습니다.")
        return payload

    async def _request(
        self,
        client: httpx.AsyncClient,
        path: str,
        *,
        params: Optional[Mapping[str, Any]] = None,
    ) -> httpx.Response:
        try:
            response = await client.get(path, params=params)
        except httpx.RequestError as exc:
            # 예외 메시지에 요청 객체나 헤더를 포함하지 않아 토큰 노출을 막는다.
            raise GithubCollectionError("GitHub API에 연결하지 못했습니다.") from exc
        if response.status_code in {403, 429} and (
            response.headers.get("X-RateLimit-Remaining") == "0"
            or response.status_code == 429
            # 보조(secondary) rate limit은 남은 횟수와 무관하게 Retry-After로 알린다.
            or "Retry-After" in response.headers
        ):
            raise GithubRateLimited(self._retry_after(response.headers))
        if response.status_code == 401:
            raise GithubAuthFailed("GitHub 토큰 인증에 실패했습니다(status=401).")
        if response.status_code == 403:
            raise GithubPermissionDenied("GitHub 접근 권한이 없습니다(status=403).")
        if response.status_code == 404:
            raise GithubRepoNotFound("GitHub 리소스를 찾을 수 없습니다(status=404).")
        if response.is_error:
            raise GithubCollectionError(
                f"GitHub API 호출이 실패했습니다(status={response.status_code})."
            )
        return response

    @staticmethod
    def _retry_after(headers: Mapping[str, str]) -> Optional[int]:
        retry_after = headers.get("Retry-After")
        if retry_after and retry_after.isdigit():
            return int(retry_after)
        reset = headers.get("X-RateLimit-Reset")
        if reset and reset.isdigit():
            return max(0, int(reset) - int(time.time()))
        return None

    @staticmethod
    def _normalize_commit(row: dict[str, Any], branch: str) -> GithubCommit:
        commit = row.get("commit") or {}
        author = commit.get("author") or commit.get("committer") or {}
        github_author = row.get("author") or {}
        parents = row.get("parents") or []
        return GithubCommit(
            sha=row["sha"],
            message=commit.get("message") or "",
            author_login=github_author.get("login"),
            author_name=author.get("name"),
            authored_at=author.get("date"),
            parent_count=len(parents),
            html_url=row["html_url"],
            branches=[branch],
        )

    @staticmethod
    def _normalize_pull(row: dict[str, Any]) -> GithubPullRequest:
        return GithubPullRequest(
            number=row["number"],
            title=row.get("title") or "",
            state=row["state"],
            author_login=(row.get("user") or {}).get("login"),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            merged_at=row.get("merged_at"),
            base_ref=(row.get("base") or {}).get("ref") or "",
            head_ref=(row.get("head") or {}).get("ref") or "",
            html_url=row["html_url"],
        )

    @staticmethod
    def _normalize_issue(row: dict[str, Any]) -> GithubIssue:
        labels = [
            label if isinstance(label, str) else str(label.get("name") or "")
            for label in row.get("labels") or []
        ]
        return GithubIssue(
            number=row["number"],
            title=row.get("title") or "",
            state=row["state"],
            author_login=(row.get("user") or {}).get("login"),
            labels=[label for label in labels if label],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            closed_at=row.get("closed_at"),
            html_url=row["html_url"],
        )
