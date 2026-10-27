"""/internal/analysis/diff-evidence·/star API의 봉투·오류 계약을 검증한다."""

from __future__ import annotations

from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from api.analysis import get_analysis_pipeline
from api.collection import get_repository_activity_port
from main import app
from ports.repository_activity import RepositoryActivityExecution
from schemas.collection import CandidateDetailResult
from services.analysis_pipeline import AnalysisPipeline
from services.diff_analyzer import CommitSummary, DiffAnalyzer, DiffSummaryOutput
from services.github_collector import GithubApiError, GithubResourceNotFound, GithubUnauthorized
from services.llm_settings import LLMConfigError
from services.llm_structured_client import (
    LLMNonRetryableError,
    LLMStructuredCallError,
    StructuredCallResult,
)
from services.star_generator import StarDraftOutput, StarField, StarGenerator

client = TestClient(app)
SHA = "a" * 40
TOKEN = "gho_" + "t" * 30
HEADERS = {"X-GitHub-Token": TOKEN}


def _sha(index: int) -> str:
    return f"{index:07x}" + "0" * 33


def _diff_body(**extra) -> dict:
    return {
        "user_repository_id": 7,
        "repository": {"github_repo_id": 1, "owner_login": "o", "name": "r", "default_branch": "main"},
        "commit_shas": [SHA],
        "github_pr_number": 12,
        "title": "JWT 로그인 구현",
        **extra,
    }


def _star_body(**extra) -> dict:
    return {
        "candidate_id": "c1",
        "target_login": "octo",
        "title": "JWT 로그인 구현",
        "source_type": "PR",
        "evidence": [
            {"sha": SHA, "message": "feat: jwt", "churn": "+1/-0", "summary": "요약", "summary_source": "DIFF"}
        ],
        **extra,
    }


def _commit(sha: str) -> dict:
    return {
        "sha": sha, "message": "feat: jwt 필터", "author_login": "octo",
        "authored_at": datetime(2026, 9, 1, tzinfo=timezone.utc), "additions": 3, "deletions": 1,
        "files": [{"path": "Filter.java", "status": "ADDED", "additions": 3, "deletions": 1, "patch": "+x"}],
    }


class _Port:
    """요청 SHA 중 ``unavailable``을 뺀 커밋을 돌려주는 가짜 GitHub 포트."""

    def __init__(
        self,
        error: Exception | None = None,
        unavailable: frozenset[str] = frozenset(),
        partial_reason: str | None = None,
    ) -> None:
        self.error = error
        self.unavailable = unavailable
        self.partial_reason = partial_reason
        self.requests: list = []
        self.tokens: list[str] = []
        self.skip_flags: list[bool] = []

    async def fetch_candidate_details(self, request, github_token, *, skip_unavailable_commits=False):
        self.requests.append(request)
        self.tokens.append(github_token)
        self.skip_flags.append(skip_unavailable_commits)
        if self.error:
            raise self.error
        return RepositoryActivityExecution(
            result=CandidateDetailResult(
                user_repository_id=request.user_repository_id,
                github_pr_number=request.github_pr_number,
                commits=[_commit(sha) for sha in request.commit_shas if sha not in self.unavailable],
                partial=self.partial_reason is not None,
                partial_reason=self.partial_reason,
            ),
            api_calls=4,
        )


class _LLM:
    def __init__(self, response, ready_error: Exception | None = None) -> None:
        self.response = response
        self.ready_error = ready_error

    def ensure_ready(self) -> None:
        if self.ready_error:
            raise self.ready_error

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        if isinstance(self.response, Exception):
            raise self.response
        if self.response is None:
            output = DiffSummaryOutput(commits=[
                CommitSummary(sha=c["sha"], summary="JWT 필터를 추가했다.", technical_points=["OncePerRequestFilter"])
                for c in payload["commits"]
            ])
        else:
            output = self.response
        return StructuredCallResult(output=output, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _pipeline(diff_llm: _LLM | None = None, star_response=None) -> AnalysisPipeline:
    empty = StarField(status="EMPTY", text=None, evidence_shas=[], insufficient_reason="근거 없음")
    return AnalysisPipeline(
        diff_analyzer=DiffAnalyzer(diff_llm or _LLM(None)),
        star_generator=StarGenerator(_LLM(star_response or StarDraftOutput(
            S=empty, T=empty, R=empty,
            A=StarField(status="FILLED", text="JWT 필터를 추가했다.", evidence_shas=[SHA], insufficient_reason=None),
        ))),
    )


@pytest.fixture
def override():
    def apply(pipeline: AnalysisPipeline, port: _Port) -> _Port:
        app.dependency_overrides[get_analysis_pipeline] = lambda: pipeline
        app.dependency_overrides[get_repository_activity_port] = lambda: port
        return port

    yield apply
    app.dependency_overrides.clear()


def _post_diff(body: dict | None = None):
    return client.post("/internal/analysis/diff-evidence", json=body or _diff_body(), headers=HEADERS)


def test_diff_evidence_returns_evidence_without_patch(override) -> None:
    port = override(_pipeline(), _Port())

    response = _post_diff()

    assert response.status_code == 200
    body = response.json()
    data = body["data"]
    assert (data["user_repository_id"], data["github_pr_number"]) == (7, 12)
    assert data["evidence"][0] == {
        "sha": SHA, "message": "feat: jwt 필터", "author_login": "octo", "churn": "+3/-1",
        "summary": "JWT 필터를 추가했다.", "summary_source": "DIFF",
        "technical_points": ["OncePerRequestFilter"], "changed_paths": ["Filter.java"], "url": None,
    }
    assert (data["partial"], data["partial_reason"], data["missing_commit_shas"]) == (False, None, [])
    assert (body["meta"]["github_calls"], body["meta"]["tool_calls_made"], body["meta"]["ended_by"]) == (4, 0, "COMPLETED")
    assert port.tokens == [TOKEN]
    assert port.skip_flags == [True]
    # PR 번호로 커밋을 다시 고르지 않고 본인 커밋 SHA만 조회한다.
    assert port.requests[0].github_pr_number is None
    assert TOKEN not in response.text
    assert "patch" not in response.text


def test_patch_truncation_alone_is_not_partial(override) -> None:
    override(_pipeline(), _Port(partial_reason="CAP_EXCEEDED"))

    data = _post_diff().json()["data"]

    assert (data["partial"], data["partial_reason"]) == (False, None)


def test_more_than_50_shas_are_cut_and_reported_as_partial(override) -> None:
    shas = [_sha(index) for index in range(1, 53)]
    port = override(_pipeline(), _Port())

    response = _post_diff(_diff_body(commit_shas=shas))

    assert response.status_code == 200
    assert response.json()["meta"]["ended_by"] == "PARTIAL"
    data = response.json()["data"]
    assert port.requests[0].commit_shas == shas[:50]
    assert len(data["evidence"]) == 50
    assert (data["partial"], data["partial_reason"]) == (True, "CAP_EXCEEDED")
    assert data["missing_commit_shas"] == shas[50:]


def test_unavailable_commit_is_skipped_and_reported(override) -> None:
    shas = [_sha(1), _sha(2)]
    override(_pipeline(), _Port(unavailable=frozenset({shas[1]})))

    data = _post_diff(_diff_body(commit_shas=shas)).json()["data"]

    assert [item["sha"] for item in data["evidence"]] == [shas[0]]
    assert (data["partial"], data["partial_reason"], data["missing_commit_shas"]) == (
        True, "COMMIT_UNAVAILABLE", [shas[1]],
    )


def test_rate_limited_missing_commits_are_reported(override) -> None:
    shas = [_sha(1), _sha(2)]
    override(_pipeline(), _Port(unavailable=frozenset({shas[1]}), partial_reason="GITHUB_RATE_LIMITED"))

    data = _post_diff(_diff_body(commit_shas=shas)).json()["data"]

    assert data["partial_reason"] == "GITHUB_RATE_LIMITED"


def test_short_sha_matches_full_sha_returned_by_github(override) -> None:
    class _FullShaPort(_Port):
        async def fetch_candidate_details(self, request, github_token, *, skip_unavailable_commits=False):
            execution = await super().fetch_candidate_details(request, github_token)
            commits = [c.model_copy(update={"sha": c.sha + "0" * (40 - len(c.sha))}) for c in execution.result.commits]
            return RepositoryActivityExecution(
                result=execution.result.model_copy(update={"commits": commits}), api_calls=1
            )

    override(_pipeline(), _FullShaPort())

    data = _post_diff(_diff_body(commit_shas=["abcdef1"])).json()["data"]

    assert data["partial"] is False


def test_diff_evidence_output_is_valid_star_input(override) -> None:
    override(_pipeline(), _Port())
    evidence = _post_diff().json()["data"]["evidence"]

    response = client.post("/internal/analysis/star", json=_star_body(evidence=evidence))

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["star"]["A"]["status"] == "FILLED"
    assert data["star"]["T"]["insufficient_reason_code"] == "NO_EVIDENCE"
    assert data["missing_fields"] == ["S", "T", "R"]


@pytest.mark.parametrize(
    ("error", "status", "code", "retryable"),
    [
        (GithubResourceNotFound("x"), 404, "RESOURCE_NOT_FOUND", False),
        (GithubUnauthorized("x", 401), 401, "GITHUB_UNAUTHORIZED", False),
        (GithubApiError("x", 403), 502, "GITHUB_API_ERROR", False),
        (GithubApiError("x", 403, secondary_rate_limited=True), 502, "GITHUB_API_ERROR", True),
        (GithubApiError("x", 422), 502, "GITHUB_API_ERROR", False),
        (GithubApiError("x", 500), 502, "GITHUB_API_ERROR", True),
        (GithubApiError("x"), 502, "GITHUB_API_ERROR", True),
    ],
)
def test_diff_evidence_maps_github_errors(override, error, status, code, retryable) -> None:
    override(_pipeline(), _Port(error=error))

    response = _post_diff()

    assert response.status_code == status
    assert (response.json()["error"]["code"], response.json()["error"]["retryable"]) == (code, retryable)


def test_llm_config_is_checked_before_github(override) -> None:
    port = override(_pipeline(_LLM(None, ready_error=LLMConfigError("GITORY_LLM_API_KEY 없음"))), _Port())

    response = _post_diff()

    assert response.status_code == 503
    error = response.json()["error"]
    assert (error["code"], error["retryable"]) == ("LLM_UNAVAILABLE", False)
    assert "GITORY_LLM_API_KEY" not in error["message"]
    assert port.requests == []


@pytest.mark.parametrize(
    ("llm_error", "retryable"),
    [(LLMNonRetryableError("model not found"), False), (LLMStructuredCallError("rate limit"), True)],
)
def test_diff_evidence_maps_llm_errors_to_503(override, llm_error, retryable) -> None:
    override(_pipeline(_LLM(llm_error)), _Port())

    response = _post_diff()

    assert response.status_code == 503
    assert (response.json()["error"]["code"], response.json()["error"]["retryable"]) == ("LLM_UNAVAILABLE", retryable)


@pytest.mark.parametrize(
    ("llm_error", "retryable"),
    [
        (LLMConfigError("missing"), False),
        (LLMNonRetryableError("bad key"), False),
        (LLMStructuredCallError("rate limit"), True),
    ],
)
def test_star_maps_llm_errors_to_503(override, llm_error, retryable) -> None:
    override(_pipeline(star_response=llm_error), _Port())

    response = client.post("/internal/analysis/star", json=_star_body())

    assert response.status_code == 503
    assert (response.json()["error"]["code"], response.json()["error"]["retryable"]) == ("LLM_UNAVAILABLE", retryable)


def test_star_rejects_empty_evidence(override) -> None:
    override(_pipeline(), _Port())

    assert client.post("/internal/analysis/star", json=_star_body(evidence=[])).status_code == 400


@pytest.mark.parametrize(
    "body",
    [
        {k: v for k, v in _diff_body().items() if k != "commit_shas"},
        _diff_body(commit_shas=[]),
        {k: v for k, v in _diff_body().items() if k != "title"},
    ],
    ids=["no-commit-shas", "empty-commit-shas", "no-title"],
)
def test_diff_evidence_rejects_invalid_requests(override, body) -> None:
    override(_pipeline(), _Port())

    assert client.post("/internal/analysis/diff-evidence", json=body, headers=HEADERS).status_code == 400


def test_diff_evidence_requires_token(override) -> None:
    override(_pipeline(), _Port())

    assert client.post("/internal/analysis/diff-evidence", json=_diff_body()).status_code == 400


def test_long_card_title_is_accepted(override) -> None:
    override(_pipeline(), _Port())

    assert _post_diff(_diff_body(title="가" * 256)).status_code == 200
    assert client.post("/internal/analysis/star", json=_star_body(title="가" * 256)).status_code == 200
