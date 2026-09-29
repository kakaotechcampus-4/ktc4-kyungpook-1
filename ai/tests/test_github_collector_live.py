"""실제 GitHub 토큰을 명시적으로 넣었을 때만 실행하는 수동 smoke test."""

from __future__ import annotations

import asyncio
import os

import pytest

from schemas.collection import CandidateDetailRequest, RepositoryCollectionRequest
from services.github_collector import CollectionLimits, GithubCollector


LIVE_ENV_NAMES = (
    "GITORY_GITHUB_LIVE_TOKEN",
    "GITORY_GITHUB_LIVE_OWNER",
    "GITORY_GITHUB_LIVE_REPO",
    "GITORY_GITHUB_LIVE_BRANCH",
    "GITORY_GITHUB_LIVE_ACTOR",
)
LIVE_ENABLED = all(os.getenv(name) for name in LIVE_ENV_NAMES)


@pytest.mark.live
@pytest.mark.skipif(
    not LIVE_ENABLED,
    reason="실제 GitHub smoke test 환경변수가 설정되지 않았습니다.",
)
def test_collects_from_real_github_with_ephemeral_token() -> None:
    """작은 테스트 저장소를 대상으로 실제 인증·응답 형식을 확인한다."""
    owner = os.environ["GITORY_GITHUB_LIVE_OWNER"]
    repo = os.environ["GITORY_GITHUB_LIVE_REPO"]
    branch = os.environ["GITORY_GITHUB_LIVE_BRANCH"]
    actor = os.environ["GITORY_GITHUB_LIVE_ACTOR"]
    request = RepositoryCollectionRequest.model_validate(
        {
            "user_repository_id": 1,
            "repository": {
                "github_repo_id": 1,
                "owner_login": owner,
                "name": repo,
                "default_branch": branch,
            },
            "actor": {"github_login": actor, "known_emails": []},
            "branches": [branch],
        }
    )
    collector = GithubCollector(
        limits=CollectionLimits(
            max_commits=5,
            max_pull_requests=1,
            max_issues=2,
        )
    )

    execution = asyncio.run(
        collector.collect(request, os.environ["GITORY_GITHUB_LIVE_TOKEN"])
    )

    assert execution.api_calls > 0
    assert execution.result.user_repository_id == 1

    if execution.result.commits:
        detail_request = CandidateDetailRequest(
            user_repository_id=1,
            repository=request.repository,
            commit_shas=[execution.result.commits[0].sha],
        )
        detail_execution = asyncio.run(
            collector.fetch_candidate_details(
                detail_request,
                os.environ["GITORY_GITHUB_LIVE_TOKEN"],
            )
        )

        assert detail_execution.api_calls > 0
        assert detail_execution.result.commits[0].sha == detail_request.commit_shas[0]
