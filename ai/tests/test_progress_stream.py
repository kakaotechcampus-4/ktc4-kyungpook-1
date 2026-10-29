"""수집·경험 그룹화 진행 스트리밍 API: 진행 줄 뒤 마지막 줄에 보통 API 와 같은 응답을 싣는다."""

from __future__ import annotations

import json

from fastapi.testclient import TestClient

import api.collection as collection_api
from api.analysis import get_analysis_pipeline
from main import create_app
from ports.repository_activity import RepositoryActivityExecution
from schemas.analysis import ExperienceGroupingResponse
from schemas.collection import RepositoryCollectionResult
from services.github_collector import GithubApiError
from services.progress import COMMITS, COMPRESS, PULL_REQUESTS, REASON, report_progress

TOKEN = {"X-GitHub-Token": "live-secret"}


def _collect_payload() -> dict:
    return {
        "user_repository_id": 123,
        "repository": {"github_repo_id": 456789, "owner_login": "grow22", "name": "gitory", "default_branch": "develop"},
        "actor": {"github_login": "grow22", "known_emails": []},
    }


def _groups_payload() -> dict:
    return {
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
    }


class ReportingPort:
    """PR 2개, 커밋 1개를 읽는 것처럼 진행을 알린 뒤 failure 가 있으면 던진다."""

    def __init__(self, failure: Exception | None = None) -> None:
        self.failure = failure

    async def collect(self, request, token):
        report_progress(PULL_REQUESTS, 0, 2)
        report_progress(PULL_REQUESTS, 2, 2)
        report_progress(COMMITS, 0, 1)
        if self.failure is not None:
            raise self.failure
        report_progress(COMMITS, 1, 1)
        result = RepositoryCollectionResult(
            user_repository_id=request.user_repository_id, head_sha=None, commits=[], pull_requests=[],
            issues=[], needs_confirmation=[], partial=False, partial_reason=None,
        )
        return RepositoryActivityExecution(result=result, api_calls=3)


class ItemReportingPort(ReportingPort):
    """먼저 커밋 하나를 읽은 값을 실어 알린 뒤 ReportingPort 처럼 숫자만 알린다."""

    async def collect(self, request, token):
        report_progress(COMMITS, 1, 1, COMMIT_ITEM)
        return await super().collect(request, token)


COMMIT_ITEM = {"kind": "COMMIT", "sha": "abc1234", "title": "feat: 기능", "files": 2, "excluded": None}


class ReportingPipeline:
    async def group_experiences(self, request, *, deadline=None):
        report_progress(COMPRESS, 0, 1)
        report_progress(COMPRESS, 1, 1)
        report_progress(REASON, 1, 1)
        return ExperienceGroupingResponse(verdict="EMPTY")


def _post(path: str, payload: dict, port: ReportingPort | None = None, headers: dict | None = None):
    app = create_app()
    if port is not None:
        app.dependency_overrides[collection_api.get_repository_activity_port] = lambda: port
    app.dependency_overrides[get_analysis_pipeline] = lambda: ReportingPipeline()
    return TestClient(app).post(path, json=payload, headers=headers or {})


def _lines(response) -> list[dict]:
    return [json.loads(line) for line in response.text.splitlines()]


def test_collect_stream_sends_progress_then_same_result_as_collect() -> None:
    response = _post("/internal/collect/stream", _collect_payload(), ReportingPort(), TOKEN)
    plain = _post("/internal/collect", _collect_payload(), ReportingPort(), TOKEN)

    lines = _lines(response)
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/x-ndjson")
    assert lines[:-1] == [
        {"type": "progress", "step": "PR_REVIEW", "done": 0, "total": 2},
        {"type": "progress", "step": "PR_REVIEW", "done": 2, "total": 2},
        {"type": "progress", "step": "COMMITS", "done": 0, "total": 1},
        {"type": "progress", "step": "COMMITS", "done": 1, "total": 1},
    ]
    assert lines[-1]["type"] == "result"
    assert lines[-1]["status"] == 200
    assert lines[-1]["body"]["data"] == plain.json()["data"]


def test_progress_line_carries_item_only_when_one_was_reported() -> None:
    lines = _lines(_post("/internal/collect/stream", _collect_payload(), ItemReportingPort(), TOKEN))

    assert lines[0] == {"type": "progress", "step": "COMMITS", "done": 1, "total": 1, "item": COMMIT_ITEM}
    assert "item" not in lines[1]


def test_collect_stream_puts_handled_error_in_last_line() -> None:
    response = _post("/internal/collect/stream", _collect_payload(), ReportingPort(GithubApiError("x")), TOKEN)

    last = _lines(response)[-1]
    assert response.status_code == 200
    assert last["status"] == 502
    assert last["body"]["success"] is False
    assert last["body"]["error"]["code"] == "GITHUB_API_ERROR"
    assert last["body"]["error"]["retryable"] is True


def test_collect_stream_turns_unexpected_error_into_internal_error_line() -> None:
    response = _post(
        "/internal/collect/stream", _collect_payload(), ReportingPort(RuntimeError("LEAK-secret-detail")), TOKEN
    )

    last = _lines(response)[-1]
    assert last["status"] == 500
    assert last["body"]["error"] == {
        "code": "INTERNAL_ERROR", "message": "AI 내부 오류로 처리하지 못했습니다.", "retryable": False,
    }
    assert last["body"]["meta"]["ended_by"] == "FAILED"
    assert "LEAK" not in response.text


def test_groups_stream_sends_grouping_progress_then_result() -> None:
    response = _post("/internal/analysis/groups/stream", _groups_payload())

    lines = _lines(response)
    assert [(line["step"], line["done"], line["total"]) for line in lines[:-1]] == [
        ("COMPRESS", 0, 1), ("COMPRESS", 1, 1), ("REASON", 1, 1),
    ]
    assert lines[-1]["status"] == 200
    assert lines[-1]["body"]["data"]["verdict"] == "EMPTY"
