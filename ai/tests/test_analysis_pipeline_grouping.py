from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import api.analysis as analysis_api
from api.analysis import get_analysis_pipeline
from main import create_app
from schemas.analysis import (
    ChangedFile,
    CommitInput,
    ExperienceCandidate,
    ExperienceGroupingRequest,
    PullRequestContext,
)
from services.analysis_pipeline import AnalysisPipeline
from services.commit_grouper import CommitGroup
from services.grouping_llm import GroupingLlmConfigError, GroupingLlmError


class FakeGroupingLlm:
    """PR 단위는 각자 경험으로 두고 나머지 단위를 한 경험으로 묶는다. merge_all이면 전부 합친다."""

    def __init__(self, *, merge_all: bool = False) -> None:
        self.merge_all = merge_all
        self.units: list[CommitGroup] = []
        self.described: list[CommitGroup] = []
        self.describe_kwargs: dict = {}
        self.describe_called_at = 0.0

    async def group_units(self, units: list[CommitGroup], **kwargs) -> list[list[CommitGroup]]:
        self.units = units
        if self.merge_all:
            return [units] if units else []
        pulls = [[unit] for unit in units if unit.pull_request_numbers]
        rest = [unit for unit in units if not unit.pull_request_numbers]
        return pulls + ([rest] if rest else [])

    async def describe(self, groups, **kwargs):
        self.described = groups
        self.describe_kwargs = kwargs
        self.describe_called_at = asyncio.get_running_loop().time()
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


def test_partial_collection_without_selected_commits_stays_partial() -> None:
    request = ExperienceGroupingRequest(
        repository_id="repo-1",
        target_login="minseo",
        commits=[commit("c" * 40, excluded=True)],
        collection_partial=True,
    )

    result = asyncio.run(
        AnalysisPipeline(grouping_llm=FakeGroupingLlm()).group_experiences(request)
    )

    assert result.verdict == "PARTIAL"
    assert result.candidates == []


def test_grouping_request_rejects_unknown_or_camel_case_fields() -> None:
    with pytest.raises(ValidationError):
        ExperienceGroupingRequest(
            repository_id="repo-1",
            target_login="minseo",
            commits=[],
            collectionPartial=True,
            typo_field="x",
        )


@pytest.mark.parametrize("source_type", ["ISSUE", "MANUAL"])
def test_experience_candidate_rejects_non_persistable_source_type(source_type: str) -> None:
    with pytest.raises(ValidationError):
        ExperienceCandidate(
            group_key="cluster:2026-09-01:aaaaaaaa",
            source_type=source_type,
            title="후보",
            reason="커밋 근거",
            score=0.5,
            commit_shas=["a" * 40],
        )


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
def test_llm_failure_returns_llm_unavailable_envelope(error, retryable, caplog) -> None:
    caplog.set_level(logging.WARNING, logger="api.analysis")
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
    # 원인은 예외 종류로만 남긴다. 예외 문구·traceback 에는 모델 출력 조각이 실릴 수 있다(#421).
    records = [record for record in caplog.records if record.name == "api.analysis"]
    assert len(records) == 1
    line = records[0].getMessage()
    assert line.startswith("경험 그룹화 실패:")
    assert "path=/internal/analysis/groups" in line
    assert f"exc={type(error).__name__}" in line
    assert f"retryable={str(retryable).lower()}" in line
    assert "secret-detail" not in caplog.text
    assert "GITORY_LLM_API_KEY" not in caplog.text
    assert records[0].exc_info is None


def test_grouping_request_deadline_reaches_description_step() -> None:
    fake = FakeGroupingLlm()
    app = create_app()
    app.dependency_overrides[get_analysis_pipeline] = lambda: AnalysisPipeline(grouping_llm=fake)

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
                }
            ],
        },
    )

    assert response.status_code == 200
    seconds_left = fake.describe_kwargs["deadline"] - fake.describe_called_at
    assert 0 < seconds_left <= analysis_api.GROUPING_DEADLINE_SECONDS


class SlowGroupingLlm(FakeGroupingLlm):
    """설명 단계에서 마감보다 오래 걸리는 LLM."""

    async def describe(self, groups, **kwargs):
        await asyncio.sleep(5)
        return await super().describe(groups, **kwargs)


def test_grouping_over_deadline_returns_retryable_llm_unavailable(monkeypatch) -> None:
    monkeypatch.setattr(analysis_api, "GROUPING_DEADLINE_SECONDS", 0.05)
    app = create_app()
    app.dependency_overrides[get_analysis_pipeline] = lambda: AnalysisPipeline(
        grouping_llm=SlowGroupingLlm()
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
                }
            ],
        },
    )

    assert response.status_code == 503
    body = response.json()
    assert body["error"]["code"] == "LLM_UNAVAILABLE"
    assert body["error"]["retryable"] is True


class DisconnectingRequest:
    """두 번째 확인부터 연결이 끊긴 것으로 답하는 요청."""

    def __init__(self) -> None:
        self.checks = 0

    async def is_disconnected(self) -> bool:
        self.checks += 1
        return self.checks > 1


def test_client_disconnect_cancels_remaining_llm_calls(monkeypatch) -> None:
    monkeypatch.setattr(analysis_api, "DISCONNECT_POLL_SECONDS", 0.01)
    calls: list[int] = []

    async def sequential_llm_calls() -> None:
        for index in range(9):
            calls.append(index)
            await asyncio.sleep(0.05)

    async def run() -> None:
        with pytest.raises(analysis_api._ClientDisconnected):
            await analysis_api._run_until_deadline_or_disconnect(
                sequential_llm_calls(), DisconnectingRequest()
            )
        await asyncio.sleep(0.2)

    asyncio.run(run())

    assert calls == [0]


def _docs_only_request() -> ExperienceGroupingRequest:
    code = commit("a" * 40, pr=17).model_copy(
        update={"files": [ChangedFile(path="src/login.py", additions=40, deletions=0)]}
    )
    docs = commit("b" * 40).model_copy(
        update={"files": [ChangedFile(path="README.md", additions=40, deletions=0)]}
    )
    return ExperienceGroupingRequest(
        repository_id="repo-1",
        target_login="minseo",
        commits=[code, docs],
        pull_requests=[PullRequestContext(number=17, title="로그인 개선")],
    )


def test_unusable_group_commits_are_reported_as_excluded() -> None:
    result = asyncio.run(
        AnalysisPipeline(grouping_llm=FakeGroupingLlm()).group_experiences(_docs_only_request())
    )

    assert [candidate.group_key for candidate in result.candidates] == ["pr:17"]
    assert result.excluded_commit_shas == ["b" * 40]


def test_unusable_group_is_not_sent_for_title_and_reason() -> None:
    fake = FakeGroupingLlm()

    asyncio.run(AnalysisPipeline(grouping_llm=fake).group_experiences(_docs_only_request()))

    assert [group.group_key for group in fake.described] == ["pr:17"]
