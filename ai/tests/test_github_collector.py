"""A-1 GitHub 원본 수집기 단위 테스트."""

from __future__ import annotations

import asyncio

import httpx
import pytest

from ports.repository_activity import RepositoryActivityPort
from schemas.collection import (
    CandidateDetailRequest,
    CollectedFileChange,
    CollectedPullRequest,
    ExclusionReason,
    FileChangeStatus,
    PartialReason,
    RepositoryCollectionRequest,
)
from services.github_collector import (
    CollectionLimits,
    GithubApiError,
    GithubCollector,
    GithubResourceNotFound,
    _contribution_decision,
    _primary_pull_by_sha,
)


def _request() -> RepositoryCollectionRequest:
    return RepositoryCollectionRequest.model_validate(
        {
            "user_repository_id": 123,
            "repository": {
                "github_repo_id": 456789,
                "owner_login": "grow22",
                "name": "gitory",
                "default_branch": "develop",
            },
            "actor": {
                "github_login": "grow22",
                "known_emails": ["grow22@example.com"],
            },
            "branches": ["feature/session-index"],
        }
    )


def _detail_request(
    *,
    commit_shas: list[str] | None = None,
    github_pr_number: int | None = 17,
) -> CandidateDetailRequest:
    return CandidateDetailRequest.model_validate(
        {
            "user_repository_id": 123,
            "repository": {
                "github_repo_id": 456789,
                "owner_login": "grow22",
                "name": "gitory",
                "default_branch": "develop",
            },
            "commit_shas": (
                ["abc1234567890"] if commit_shas is None else commit_shas
            ),
            "github_pr_number": github_pr_number,
        }
    )


def _success_handler(request: httpx.Request) -> httpx.Response:
    assert request.headers["Authorization"] == "Bearer live-secret"
    assert request.headers["X-GitHub-Api-Version"] == "2022-11-28"
    assert request.headers["User-Agent"] == "Gitory/1.0"

    path = request.url.path
    if path == "/repos/grow22/gitory/pulls":
        return httpx.Response(
            200,
            json=[
                {
                    "number": 17,
                    "title": "세션 조회 성능 개선",
                    "body": "인덱스를 추가합니다. Resolves #42",
                    "state": "closed",
                    "user": {"login": "grow22"},
                    "base": {"ref": "develop"},
                    "head": {"ref": "feature/session-index"},
                    "created_at": "2026-09-20T10:00:00Z",
                    "merged_at": "2026-09-21T11:00:00Z",
                }
            ],
        )
    if path == "/repos/grow22/gitory/pulls/17/commits":
        return httpx.Response(200, json=[{"sha": "abc1234567890"}])
    if path == "/repos/grow22/gitory/pulls/17/reviews":
        return httpx.Response(
            200,
            json=[
                {
                    "id": 9001,
                    "state": "CHANGES_REQUESTED",
                    "body": "책임 분리를 명확히 해주세요.",
                    "user": {"login": "reviewer"},
                    "submitted_at": "2026-09-20T12:00:00Z",
                }
            ],
        )
    if path == "/repos/grow22/gitory/pulls/17/comments":
        return httpx.Response(
            200,
            json=[
                {
                    "id": 9101,
                    "pull_request_review_id": 9001,
                    "body": "인덱스 책임을 repository 계층으로 옮겨주세요.",
                    "path": "backend/session.sql",
                    "line": 12,
                    "user": {"login": "reviewer"},
                    "created_at": "2026-09-20T11:50:00Z",
                }
            ],
        )
    if path == "/repos/grow22/gitory/branches/develop":
        return httpx.Response(
            200,
            json={"name": "develop", "commit": {"sha": "abc1234567890"}},
        )
    if path == "/repos/grow22/gitory/commits/abc1234567890":
        return httpx.Response(
            200,
            json={
                "sha": "abc1234567890",
                "author": {"login": "grow22", "type": "User"},
                "commit": {
                    "message": "feat: 세션 인덱스 추가 (#42)",
                    "author": {
                        "name": "Grow",
                        "email": "grow22@example.com",
                        "date": "2026-09-20T09:00:00Z",
                    },
                },
                "parents": [{"sha": "parent"}],
                "stats": {"additions": 12, "deletions": 3},
                "files": [
                    {
                        "filename": "backend/session.sql",
                        "status": "modified",
                        "additions": 10,
                        "deletions": 3,
                        "patch": "@@ -1 +1 @@",
                    },
                    {
                        "filename": "docs/index.png",
                        "status": "added",
                        "additions": 0,
                        "deletions": 0,
                    },
                ],
            },
        )
    if path == "/repos/grow22/gitory/commits":
        return httpx.Response(200, json=[{"sha": "abc1234567890"}])
    if path == "/repos/grow22/gitory/issues":
        return httpx.Response(
            200,
            json=[
                {
                    "number": 17,
                    "title": "PR은 issue 목록에서도 보인다",
                    "pull_request": {"url": "https://api.github.test/pulls/17"},
                },
                {
                    "number": 42,
                    "title": "세션 조회가 느립니다",
                    "body": "응답 시간이 오래 걸립니다.",
                    "state": "closed",
                    "user": {"login": "reporter"},
                    "labels": [{"name": "performance"}],
                    "created_at": "2026-09-19T08:00:00Z",
                    "closed_at": "2026-09-21T11:00:00Z",
                },
            ],
        )
    return httpx.Response(500, json={"unexpected": str(request.url)})


def test_collects_commit_diff_pull_review_and_issue() -> None:
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(_success_handler),
    )

    execution = asyncio.run(collector.collect(_request(), "live-secret"))
    result = execution.result

    assert execution.api_calls == 8
    assert result.partial is False
    assert len(result.commits) == 1
    assert len(result.pull_requests) == 1
    assert len(result.issues) == 1
    assert result.commits[0].pull_request_number == 17
    assert result.commits[0].issue_numbers == [42]
    assert result.pull_requests[0].reviews[0].state == "CHANGES_REQUESTED"
    assert result.pull_requests[0].linked_issue_numbers == [42]
    assert (
        result.pull_requests[0].reviews[0].body_excerpt
        == "책임 분리를 명확히 해주세요."
    )
    assert (
        result.pull_requests[0].reviews[0].comments[0].body_excerpt
        == "인덱스 책임을 repository 계층으로 옮겨주세요."
    )
    assert result.issues[0].body_excerpt == "응답 시간이 오래 걸립니다."
    assert not hasattr(result.commits[0].files[0], "patch")
    assert result.head_sha == "abc1234567890"


def test_collects_only_commits_after_since_and_records_default_branch_head() -> None:
    """재수집은 GitHub 목록 API의 since로 상세 커밋 조회 대상을 줄인다."""
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return _success_handler(request)

    payload = _request().model_dump(mode="json")
    payload["branches"] = ["develop"]
    payload["since"] = "2026-09-20T00:00:00Z"
    request = RepositoryCollectionRequest.model_validate(payload)
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(handler),
    )

    result = asyncio.run(collector.collect(request, "live-secret")).result

    commit_list_call = next(
        call for call in calls if call.url.path == "/repos/grow22/gitory/commits"
    )
    assert commit_list_call.url.params["since"] == "2026-09-20T00:00:00Z"
    assert result.head_sha == "abc1234567890"


def test_incremental_collection_keeps_head_and_skips_old_commit_details() -> None:
    """변경 없는 재수집은 head를 보존하면서 commit 상세 호출을 생략한다."""
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        path = request.url.path
        if path == "/repos/grow22/gitory/pulls":
            return httpx.Response(200, json=[])
        if path == "/repos/grow22/gitory/branches/develop":
            return httpx.Response(
                200,
                json={"name": "develop", "commit": {"sha": "dead1234567890"}},
            )
        if path == "/repos/grow22/gitory/commits":
            assert request.url.params["since"] == "2026-09-21T00:00:00Z"
            return httpx.Response(200, json=[])
        if path == "/repos/grow22/gitory/issues":
            return httpx.Response(200, json=[])
        return httpx.Response(500, json={"unexpected": str(request.url)})

    payload = _request().model_dump(mode="json")
    payload["branches"] = ["develop"]
    payload["since"] = "2026-09-21T00:00:00Z"
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(handler),
    )

    execution = asyncio.run(
        collector.collect(
            RepositoryCollectionRequest.model_validate(payload),
            "live-secret",
        )
    )

    assert execution.result.head_sha == "dead1234567890"
    assert execution.result.commits == []
    assert execution.api_calls == 4
    assert not any(
        request.url.path.startswith("/repos/grow22/gitory/commits/")
        for request in calls
    )


def test_incremental_collection_fetches_detail_only_for_new_commit() -> None:
    """새 커밋 한 건만 생기면 그 SHA의 상세 diff만 다시 조회한다."""
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        path = request.url.path
        if path == "/repos/grow22/gitory/pulls":
            return httpx.Response(200, json=[])
        if path == "/repos/grow22/gitory/branches/develop":
            return httpx.Response(
                200,
                json={"name": "develop", "commit": {"sha": "def1234567890"}},
            )
        if path == "/repos/grow22/gitory/commits":
            return httpx.Response(200, json=[{"sha": "def1234567890"}])
        if path == "/repos/grow22/gitory/commits/def1234567890":
            return httpx.Response(
                200,
                json={
                    "sha": "def1234567890",
                    "author": {"login": "grow22", "type": "User"},
                    "commit": {
                        "message": "feat: 새 커밋",
                        "author": {
                            "name": "Grow",
                            "email": "grow22@example.com",
                            "date": "2026-09-21T01:00:00Z",
                        },
                    },
                    "parents": [{"sha": "parent"}],
                    "stats": {"additions": 1, "deletions": 0},
                    "files": [],
                },
            )
        if path == "/repos/grow22/gitory/issues":
            return httpx.Response(200, json=[])
        return httpx.Response(500, json={"unexpected": str(request.url)})

    payload = _request().model_dump(mode="json")
    payload["branches"] = ["develop"]
    payload["since"] = "2026-09-21T00:00:00Z"
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(handler),
    )

    execution = asyncio.run(
        collector.collect(
            RepositoryCollectionRequest.model_validate(payload),
            "live-secret",
        )
    )

    detail_calls = [
        request
        for request in calls
        if request.url.path.startswith("/repos/grow22/gitory/commits/")
    ]
    assert [request.url.path for request in detail_calls] == [
        "/repos/grow22/gitory/commits/def1234567890"
    ]
    assert [commit.sha for commit in execution.result.commits] == [
        "def1234567890"
    ]
    assert execution.api_calls == 5


def test_recollection_call_count_drops_when_repository_is_unchanged() -> None:
    """같은 저장소의 최초/무변경/1건 추가 수집 호출 수를 비교한다."""
    commit_shas = ["aaa1111111111", "bbb2222222222", "ccc3333333333"]

    def execution_for(*, listed_shas: list[str], since: str | None):
        def handler(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path == "/repos/grow22/gitory/pulls":
                return httpx.Response(200, json=[])
            if path == "/repos/grow22/gitory/branches/develop":
                return httpx.Response(
                    200,
                    json={"commit": {"sha": commit_shas[-1]}},
                )
            if path == "/repos/grow22/gitory/commits":
                return httpx.Response(
                    200,
                    json=[{"sha": sha} for sha in listed_shas],
                )
            if path.startswith("/repos/grow22/gitory/commits/"):
                sha = path.rsplit("/", 1)[-1]
                return httpx.Response(
                    200,
                    json={
                        "sha": sha,
                        "author": {"login": "grow22", "type": "User"},
                        "commit": {
                            "message": f"feat: {sha}",
                            "author": {
                                "name": "Grow",
                                "email": "grow22@example.com",
                                "date": "2026-09-21T01:00:00Z",
                            },
                        },
                        "parents": [{"sha": "parent"}],
                        "stats": {"additions": 1, "deletions": 0},
                        "files": [],
                    },
                )
            if path == "/repos/grow22/gitory/issues":
                return httpx.Response(200, json=[])
            return httpx.Response(500, json={"unexpected": str(request.url)})

        payload = _request().model_dump(mode="json")
        payload["branches"] = ["develop"]
        payload["since"] = since
        collector = GithubCollector(
            base_url="https://api.github.test",
            transport=httpx.MockTransport(handler),
        )
        return asyncio.run(
            collector.collect(
                RepositoryCollectionRequest.model_validate(payload),
                "live-secret",
            )
        )

    initial = execution_for(listed_shas=commit_shas, since=None)
    unchanged = execution_for(
        listed_shas=[],
        since="2026-09-21T02:00:00Z",
    )
    one_new_commit = execution_for(
        listed_shas=[commit_shas[-1]],
        since="2026-09-21T00:30:00Z",
    )

    assert initial.api_calls == 7
    assert unchanged.api_calls == 4
    assert one_new_commit.api_calls == 5
    assert unchanged.result.head_sha == commit_shas[-1]
    assert one_new_commit.result.head_sha == commit_shas[-1]


def test_github_collector_satisfies_repository_activity_port() -> None:
    """A는 GitHub 구체 클래스 대신 저장소 활동 포트에 의존한다."""
    assert isinstance(GithubCollector(), RepositoryActivityPort)


def test_fetches_only_selected_commit_diff_with_patch_limit() -> None:
    """선택된 SHA의 patch만 조회하고 개별 상한을 적용한다."""
    collector = GithubCollector(
        base_url="https://api.github.test",
        limits=CollectionLimits(
            max_patch_chars=5,
            max_candidate_patch_chars=100,
        ),
        transport=httpx.MockTransport(_success_handler),
    )

    execution = asyncio.run(
        collector.fetch_candidate_details(
            _detail_request(),
            "live-secret",
        )
    )

    assert execution.api_calls == 1
    assert len(execution.result.commits) == 1
    assert execution.result.commits[0].sha == "abc1234567890"
    assert execution.result.commits[0].files[0].patch == "@@ -1"
    assert execution.result.commits[0].files[0].patch_truncated is True
    assert execution.result.commits[0].files[1].patch is None
    assert execution.result.commits[0].files[1].patch_truncated is False
    assert execution.result.partial is True
    assert execution.result.partial_reason == PartialReason.CAP_EXCEEDED


def test_candidate_detail_resolves_commit_shas_from_pr() -> None:
    """SHA가 없는 PR 후보는 PR commit 목록을 먼저 조회한다."""
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(_success_handler),
    )

    execution = asyncio.run(
        collector.fetch_candidate_details(
            _detail_request(commit_shas=[], github_pr_number=17),
            "live-secret",
        )
    )

    assert execution.api_calls == 2
    assert execution.result.github_pr_number == 17
    assert [item.sha for item in execution.result.commits] == ["abc1234567890"]


def test_candidate_detail_rate_limit_returns_partial_result() -> None:
    """상세 diff 조회도 호출 제한 시 수집된 분량만 부분 결과로 반환한다."""
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                403,
                headers={"X-RateLimit-Remaining": "0"},
            )
        ),
    )

    execution = asyncio.run(
        collector.fetch_candidate_details(
            _detail_request(),
            "live-secret",
        )
    )

    assert execution.result.commits == []
    assert execution.result.partial is True
    assert execution.result.partial_reason == PartialReason.GITHUB_RATE_LIMITED


def test_rate_limit_returns_partial_result() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(403, headers={"X-RateLimit-Remaining": "0"})

    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(handler),
    )

    execution = asyncio.run(collector.collect(_request(), "live-secret"))

    assert execution.result.partial is True
    assert execution.result.partial_reason == PartialReason.GITHUB_RATE_LIMITED
    assert execution.api_calls == 1


def test_missing_repository_is_not_hidden_as_rate_limit() -> None:
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(lambda _: httpx.Response(404)),
    )

    with pytest.raises(GithubResourceNotFound):
        asyncio.run(collector.collect(_request(), "live-secret"))


def test_permission_failure_is_an_api_error() -> None:
    collector = GithubCollector(
        base_url="https://api.github.test",
        transport=httpx.MockTransport(lambda _: httpx.Response(403)),
    )

    with pytest.raises(GithubApiError, match="status=403"):
        asyncio.run(collector.collect(_request(), "live-secret"))


def test_blank_token_is_rejected_before_network_call() -> None:
    collector = GithubCollector(
        transport=httpx.MockTransport(
            lambda _: pytest.fail("빈 토큰이면 GitHub를 호출하면 안 됩니다.")
        )
    )

    with pytest.raises(ValueError, match="토큰"):
        asyncio.run(collector.collect(_request(), "   "))


def test_limit_marks_result_partial() -> None:
    collector = GithubCollector(
        base_url="https://api.github.test",
        limits=CollectionLimits(max_pull_requests=0, max_commits=1, max_issues=1),
        transport=httpx.MockTransport(_success_handler),
    )

    execution = asyncio.run(collector.collect(_request(), "live-secret"))

    assert execution.result.partial is True
    assert execution.result.partial_reason == PartialReason.CAP_EXCEEDED


@pytest.mark.parametrize(
    ("login", "author_type", "parent_count", "path", "expected"),
    [
        ("dependabot[bot]", "Bot", 1, "src/app.py", ExclusionReason.BOT),
        ("grow22", "User", 2, "src/app.py", ExclusionReason.MERGE_COMMIT),
        ("grow22", "User", 1, "package-lock.json", ExclusionReason.LOCKFILE_ONLY),
        ("another", "User", 1, "src/app.py", ExclusionReason.NOT_OWN),
    ],
)
def test_deterministic_commit_exclusion_rules(
    login: str,
    author_type: str,
    parent_count: int,
    path: str,
    expected: ExclusionReason,
) -> None:
    files = [
        CollectedFileChange(
            path=path,
            status=FileChangeStatus.MODIFIED,
            additions=1,
            deletions=0,
        )
    ]

    reason, _ = _contribution_decision(
        sha="abc1234567890",
        login=login,
        author_type=author_type,
        author_name="Another",
        author_email="another@example.com",
        parent_count=parent_count,
        files=files,
        request=_request(),
    )

    assert reason == expected


def test_known_email_is_treated_as_own_contribution() -> None:
    reason, confirmation = _contribution_decision(
        sha="abc1234567890",
        login=None,
        author_type=None,
        author_name="Grow",
        author_email="grow22@example.com",
        parent_count=1,
        files=[],
        request=_request(),
    )

    assert reason is None
    assert confirmation is None


def test_similar_author_name_is_left_for_user_confirmation() -> None:
    reason, confirmation = _contribution_decision(
        sha="abc1234567890",
        login=None,
        author_type=None,
        author_name="grow22",
        author_email="unknown@example.com",
        parent_count=1,
        files=[],
        request=_request(),
    )

    assert reason == ExclusionReason.NOT_OWN
    assert confirmation is not None
    assert confirmation.sha == "abc1234567890"


def test_commit_in_feature_and_release_pr_maps_to_narrowest_pr() -> None:
    def pull(number: int, shas: list[str], base: str) -> CollectedPullRequest:
        return CollectedPullRequest(
            number=number,
            title=f"PR {number}",
            state="CLOSED",
            base_branch=base,
            head_branch=f"branch-{number}",
            created_at="2026-09-20T10:00:00Z",
            commit_shas=shas,
        )

    feature_a = pull(17, ["a" * 40, "b" * 40], "develop")
    feature_b = pull(18, ["c" * 40], "develop")
    # 최근 갱신순으로 먼저 오는 develop → main 릴리스 PR이 모든 커밋을 다시 담는다.
    release = pull(30, ["a" * 40, "b" * 40, "c" * 40, "d" * 40], "main")

    mapping = _primary_pull_by_sha([release, feature_a, feature_b])

    assert mapping == {"a" * 40: 17, "b" * 40: 17, "c" * 40: 18, "d" * 40: 30}
