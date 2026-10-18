"""처리한 실패의 원인 로그와 처리하지 못한 예외의 봉투 500을 검증한다."""

from __future__ import annotations

import asyncio
import logging
import socket
import threading
import time

import httpx
import pytest
import uvicorn
from fastapi.testclient import TestClient

from api.analysis import get_analysis_pipeline
from api.collection import get_repository_activity_port
from api.failure_log import log_failure
from main import app, create_app
from services.diff_analyzer import DiffAnalysisError
from services.github_collector import GithubApiError, GithubResourceNotFound
from services.llm_settings import LLMConfigError
from services.star_generator import StarGenerationError

TOKEN = "gho_" + "t" * 30
LEAK = "LEAK-원문-조각"
SHA = "a" * 40
REPOSITORY = {"github_repo_id": 1, "owner_login": "LEAK-owner", "name": "LEAK-repo", "default_branch": "main"}
LOGIN = "LEAK-login"
HEADERS = {"X-GitHub-Token": TOKEN}


class _FailingPort:
    def __init__(self, error: Exception) -> None:
        self.error = error

    async def collect(self, request, github_token):
        raise self.error

    async def fetch_candidate_details(self, request, github_token, *, skip_unavailable_commits=False):
        raise self.error


class _FailingPipeline:
    def __init__(self, error: Exception) -> None:
        self.error = error

    async def collect_diff_evidence(self, request, github_token, activity_port):
        raise self.error

    async def analyze_star(self, request):
        raise self.error


def _caused(error: Exception, cause: Exception) -> Exception:
    error.__cause__ = cause
    return error


def _collect_body() -> dict:
    return {"user_repository_id": 7, "repository": REPOSITORY, "actor": {"github_login": LOGIN}}


def _details_body() -> dict:
    return {"user_repository_id": 7, "repository": REPOSITORY, "commit_shas": [SHA, "b" * 40]}


def _diff_body() -> dict:
    return {"user_repository_id": 7, "repository": REPOSITORY, "commit_shas": [SHA], "title": LEAK}


def _star_body() -> dict:
    return {
        "candidate_id": "c1", "target_login": LOGIN, "title": LEAK, "source_type": "PR",
        "evidence": [{"sha": SHA, "message": LEAK, "churn": "+1/-0", "summary": LEAK, "summary_source": "DIFF"}],
    }


#: (요청 경로, 본문, 던질 예외, 로그 접두어, 로그에 들어가야 할 조각들)
_CASES = {
    "collect": (
        "/internal/collect", _collect_body, GithubApiError(LEAK, 500), "GitHub 활동 수집 실패:",
        ["code=GITHUB_API_ERROR", "retryable=true", "exc=GithubApiError", "github_status=500", "user_repository_id=7"],
    ),
    "collect-not-found": (
        "/internal/collect", _collect_body, GithubResourceNotFound(LEAK, 404), "GitHub 활동 수집 실패:",
        ["code=RESOURCE_NOT_FOUND", "retryable=false", "exc=GithubResourceNotFound", "github_status=404"],
    ),
    "candidate-details-not-found": (
        "/internal/collect/candidate-details", _details_body, GithubResourceNotFound(LEAK, 404), "후보 diff 수집 실패:",
        ["code=RESOURCE_NOT_FOUND", "retryable=false", "exc=GithubResourceNotFound", "github_status=404"],
    ),
    "candidate-details": (
        "/internal/collect/candidate-details", _details_body, GithubApiError(LEAK, 502), "후보 diff 수집 실패:",
        ["code=GITHUB_API_ERROR", "exc=GithubApiError", "user_repository_id=7", "commits=2"],
    ),
    "diff-evidence": (
        "/internal/analysis/diff-evidence", _diff_body, _caused(DiffAnalysisError(LEAK, retryable=True), ValueError(LEAK)),
        "diff 근거 생성 실패:",
        [
            "code=LLM_UNAVAILABLE", "retryable=true", "exc=DiffAnalysisError", "cause=ValueError",
            "user_repository_id=7", "commits=1",
        ],
    ),
    "diff-evidence-root": (
        "/internal/analysis/diff-evidence", _diff_body,
        _caused(DiffAnalysisError(LEAK, retryable=True), _caused(RuntimeError(LEAK), TimeoutError(LEAK))),
        "diff 근거 생성 실패:", ["cause=RuntimeError", "root=TimeoutError"],
    ),
    "diff-evidence-deadline": (
        "/internal/analysis/diff-evidence", _diff_body,
        _caused(DiffAnalysisError(LEAK, retryable=True), _caused(TimeoutError(LEAK), asyncio.CancelledError())),
        "diff 근거 생성 실패:", ["cause=TimeoutError", "root=TimeoutError"],
    ),
    "diff-evidence-github": (
        "/internal/analysis/diff-evidence", _diff_body, GithubApiError(LEAK, 403, secondary_rate_limited=True),
        "diff 근거 생성 실패:", ["code=GITHUB_API_ERROR", "retryable=true", "github_status=403"],
    ),
    "diff-evidence-github-permanent": (
        "/internal/analysis/diff-evidence", _diff_body, GithubApiError(LEAK, 401), "diff 근거 생성 실패:",
        ["code=GITHUB_API_ERROR", "retryable=false", "github_status=401"],
    ),
    "diff-evidence-not-found": (
        "/internal/analysis/diff-evidence", _diff_body, GithubResourceNotFound(LEAK, 404), "diff 근거 생성 실패:",
        ["code=RESOURCE_NOT_FOUND", "retryable=false", "exc=GithubResourceNotFound"],
    ),
    "diff-evidence-llm-config": (
        "/internal/analysis/diff-evidence", _diff_body, LLMConfigError(LEAK), "diff 근거 생성 실패:",
        ["code=LLM_UNAVAILABLE", "retryable=false", "exc=LLMConfigError"],
    ),
    "star-llm-config": (
        "/internal/analysis/star", _star_body, LLMConfigError(LEAK), "STAR 초안 생성 실패:",
        ["code=LLM_UNAVAILABLE", "retryable=false", "exc=LLMConfigError"],
    ),
    "star": (
        "/internal/analysis/star", _star_body, StarGenerationError(LEAK, retryable=False), "STAR 초안 생성 실패:",
        ["code=LLM_UNAVAILABLE", "retryable=false", "exc=StarGenerationError", "candidate_id='c1'", "evidence=1"],
    ),
}


@pytest.fixture
def failing():
    def apply(error: Exception) -> None:
        app.dependency_overrides[get_repository_activity_port] = lambda: _FailingPort(error)
        app.dependency_overrides[get_analysis_pipeline] = lambda: _FailingPipeline(error)

    yield apply
    app.dependency_overrides.clear()


def _post(client: TestClient, path: str, body: dict):
    return client.post(path, json=body, headers=HEADERS)


@pytest.mark.parametrize("case", _CASES.values(), ids=_CASES.keys())
def test_handled_failure_logs_one_warning_line_with_cause(failing, caplog, case) -> None:
    path, body, error, prefix, fragments = case
    failing(error)

    with caplog.at_level(logging.WARNING):
        response = _post(TestClient(app), path, body())

    assert response.status_code in (404, 502, 503)
    records = [record for record in caplog.records if record.name.startswith("api.")]
    assert len(records) == 1
    line = records[0].getMessage()
    assert records[0].levelno == logging.WARNING
    assert line.startswith(prefix)
    assert f"path={path}" in line
    assert all(fragment in line for fragment in fragments)


@pytest.mark.parametrize("case", _CASES.values(), ids=_CASES.keys())
def test_handled_failure_log_has_no_secret_or_original_text(failing, caplog, case) -> None:
    path, body, error, _, _ = case
    failing(error)

    with caplog.at_level(logging.WARNING):
        _post(TestClient(app), path, body())

    assert TOKEN not in caplog.text
    assert all(leak not in caplog.text for leak in (LEAK, "LEAK-owner", "LEAK-repo", LOGIN))
    assert all(record.exc_info is None for record in caplog.records)


def test_validation_failure_logs_only_field_locations_and_kinds(caplog) -> None:
    body = {
        "candidate_id": LEAK, "target_login": LOGIN, "title": LEAK, "source_type": LEAK,
        "evidence": LEAK, "extra_a": LEAK, "extra_b": LEAK, "extra_c": LEAK,
        "extra_d": LEAK, "extra_e": LEAK,
    }

    with caplog.at_level(logging.WARNING):
        response = _post(TestClient(app), "/internal/analysis/star", body)

    assert response.status_code == 400
    records = [record for record in caplog.records if record.name == "main"]
    assert len(records) == 1
    line = records[0].getMessage()
    assert line == (
        "AI 요청 검증 실패: path=/internal/analysis/star errors=body.source_type:literal_error, "
        "body.evidence:list_type, body.extra_a:extra_forbidden, body.extra_b:extra_forbidden, "
        "body.extra_c:extra_forbidden"
    )
    assert TOKEN not in caplog.text
    assert all(leak not in caplog.text for leak in (LEAK, LOGIN))


def _self_cycle() -> BaseException:
    error = RuntimeError("cycle")
    error.__cause__ = error
    return error


@pytest.mark.parametrize("error", [RuntimeError("alone"), _self_cycle()], ids=["no-cause", "self-cycle"])
def test_root_is_dash_when_there_is_no_inner_cause(error, caplog) -> None:
    with caplog.at_level(logging.WARNING):
        log_failure(
            logging.getLogger("api.test"), "시험", path="/p", code="X", retryable=False,
            exc=error, started=time.monotonic(),
        )

    assert " root=- " in caplog.records[0].getMessage()


def test_unexpected_exception_becomes_internal_error_envelope(failing) -> None:
    failing(KeyError(LEAK))

    response = _post(TestClient(app, raise_server_exceptions=False), "/internal/analysis/star", _star_body())

    assert response.status_code == 500
    body = response.json()
    assert body["success"] is False
    assert (body["error"]["code"], body["error"]["retryable"]) == ("INTERNAL_ERROR", False)
    assert LEAK not in response.text


def test_unexpected_exception_log_is_one_error_line_without_traceback(failing, caplog) -> None:
    failing(KeyError(LEAK))

    with caplog.at_level(logging.WARNING):
        _post(TestClient(app, raise_server_exceptions=False), "/internal/analysis/star", _star_body())

    records = [record for record in caplog.records if record.name == "main"]
    assert len(records) == 1
    assert records[0].levelno == logging.ERROR
    assert records[0].getMessage() == "AI 처리 못 한 예외: path=/internal/analysis/star exc=KeyError"
    assert records[0].exc_info is None


def test_client_reads_whole_envelope_over_real_http_after_unexpected_exception() -> None:
    application = create_app(docs_enabled=False)

    @application.get("/boom")
    async def boom():
        raise KeyError(LEAK)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(
            application, host="127.0.0.1", port=0, loop="asyncio", http="h11", lifespan="off",
            log_level="critical", access_log=False, timeout_graceful_shutdown=1,
        ))
        thread = threading.Thread(target=lambda: server.run(sockets=[listener]), daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 5
            while not server.started and thread.is_alive() and time.monotonic() < deadline:
                time.sleep(0.01)
            assert server.started
            with httpx.Client(timeout=2, trust_env=False) as client:
                response = client.get(f"http://127.0.0.1:{port}/boom")
            assert response.status_code == 500
            assert response.json()["error"]["code"] == "INTERNAL_ERROR"
        finally:
            server.should_exit = True
            thread.join(timeout=5)


def test_app_configures_logging_with_level_and_logger_name() -> None:
    root = logging.getLogger()
    saved_handlers, saved_level = root.handlers[:], root.level
    root.handlers[:] = []
    try:
        create_app(docs_enabled=False)

        assert root.level == logging.WARNING
        record = logging.LogRecord("api.analysis", logging.WARNING, __file__, 1, "메시지", None, None)
        assert root.handlers[0].format(record) == "WARNING api.analysis: 메시지"
    finally:
        root.handlers[:] = saved_handlers
        root.setLevel(saved_level)
