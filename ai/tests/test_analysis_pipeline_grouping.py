from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from api.analysis import get_analysis_pipeline
from main import create_app
from schemas.analysis import CommitInput, ExperienceGroupingRequest, PullRequestContext
from services.analysis_pipeline import AnalysisPipeline
from services.commit_grouper import CommitGroup
from services.grouping_llm import GroupingLlmConfigError, GroupingLlmError


class FakeGroupingLlm:
    """PR 단위는 각자 경험으로 두고 나머지 단위를 한 경험으로 묶는다. merge_all이면 전부 합친다."""

    def __init__(self, *, merge_all: bool = False) -> None:
        self.merge_all = merge_all
        self.units: list[CommitGroup] = []
        self.described: list[CommitGroup] = []

    async def group_units(self, units: list[CommitGroup], **kwargs) -> list[list[CommitGroup]]:
        self.units = units
        if self.merge_all:
            return [units] if units else []
        pulls = [[unit] for unit in units if unit.pull_request_numbers]
        rest = [unit for unit in units if not unit.pull_request_numbers]
        return pulls + ([rest] if rest else [])

    async def describe(self, groups, **kwargs):
        self.described = groups
        return {
            group.group_key: (f"{group.source_type} 기능", "커밋 근거로 선정")
            for group in groups
        }


def commit(sha: str, *, pr: int | None = None, excluded: bool = False) -> CommitInput:
    return CommitInput(
        sha=sha,
        message="feat: 기능 구현",
        author_login="minseo",
        authored_at=datetime(2026, 9, 1, tzinfo=timezone.utc),
        parent_count=1,
        pull_request_number=pr,
        is_excluded=excluded,
        exclusion_reason="NOT_OWN" if excluded else None,
    )


def test_group_experiences_connects_rules_llm_and_score_sorting() -> None:
    fake = FakeGroupingLlm()
    request = ExperienceGroupingRequest(
        repository_id="repo-1",
        target_login="minseo",
        commits=[
            commit("a" * 40, pr=17),
            commit("b" * 40),
            commit("c" * 40, excluded=True),
        ],
        pull_requests=[PullRequestContext(number=17, title="로그인 개선")],
    )

    result = asyncio.run(AnalysisPipeline(grouping_llm=fake).group_experiences(request))

    assert result.verdict == "OK"
    assert result.excluded_commit_shas == ["c" * 40]
    assert [candidate.group_key for candidate in result.candidates] == [
        "pr:17",
        "cluster:2026-09-01:bbbbbbbb",
    ]
    assert [unit.group_key for unit in fake.units] == ["pr:17", "cluster:2026-09-01:bbbbbbbb"]
    assert {group.group_key for group in fake.described} == {
        "pr:17",
        "cluster:2026-09-01:bbbbbbbb",
    }


def test_group_experiences_returns_empty_without_calling_llm() -> None:
    fake = FakeGroupingLlm()
    request = ExperienceGroupingRequest(
        repository_id="repo-1",
        target_login="minseo",
        commits=[commit("c" * 40, excluded=True)],
    )

    result = asyncio.run(AnalysisPipeline(grouping_llm=fake).group_experiences(request))

    assert result.verdict == "EMPTY"
    assert result.candidates == []
    assert result.excluded_commit_shas == ["c" * 40]
    assert fake.units == []


def test_partial_collection_marks_verdict_partial() -> None:
    request = ExperienceGroupingRequest(
        repository_id="repo-1",
        target_login="minseo",
        commits=[commit("a" * 40, pr=17)],
        collection_partial=True,
    )

    result = asyncio.run(
        AnalysisPipeline(grouping_llm=FakeGroupingLlm()).group_experiences(request)
    )

    assert result.verdict == "PARTIAL"
    assert len(result.candidates) == 1


def test_pr_and_follow_up_commit_become_one_experience_candidate() -> None:
    request = ExperienceGroupingRequest(
        repository_id="repo-1",
        target_login="minseo",
        commits=[commit("a" * 40, pr=17), commit("b" * 40, pr=21), commit("c" * 40)],
        pull_requests=[PullRequestContext(number=17, title="로그인 API")],
    )

    result = asyncio.run(
        AnalysisPipeline(grouping_llm=FakeGroupingLlm(merge_all=True)).group_experiences(request)
    )

    [candidate] = result.candidates
    assert candidate.source_type == "PR"
    assert candidate.pull_request_number == 17
    assert candidate.pull_request_numbers == [17, 21]
    assert candidate.commit_shas == ["a" * 40, "b" * 40, "c" * 40]


class FailingGroupingLlm(FakeGroupingLlm):
    def __init__(self, error: Exception) -> None:
        super().__init__()
        self.error = error

    async def describe(self, groups, **kwargs):
        raise self.error


@pytest.mark.parametrize(
    ("error", "retryable"),
    [
        (GroupingLlmError("모델 호출 실패 secret-detail"), True),
        (GroupingLlmConfigError("GITORY_LLM_API_KEY 없음"), False),
    ],
)
def test_llm_failure_returns_llm_unavailable_envelope(error, retryable) -> None:
    app = create_app()
    app.dependency_overrides[get_analysis_pipeline] = lambda: AnalysisPipeline(
        grouping_llm=FailingGroupingLlm(error)
    )
    response = TestClient(app).post(
        "/internal/analysis/groups",
        json={
            "repository_id": "repo-1",
            "target_login": "minseo",
            "commits": [
                {
                    "sha": "a" * 40,
                    "message": "feat: 기능",
                    "author_login": "minseo",
                    "authored_at": "2026-09-01T00:00:00Z",
                    "parent_count": 1,
                    "pull_request_number": 17,
                }
            ],
        },
    )

    assert response.status_code == 503
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_UNAVAILABLE"
    assert body["error"]["retryable"] is retryable
    assert "secret-detail" not in response.text
    assert "GITORY_LLM_API_KEY" not in response.text
