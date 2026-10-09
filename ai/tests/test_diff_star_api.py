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
from services.github_collector import GithubApiError, GithubResourceNotFound
from services.llm_settings import LLMConfigError
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult
from services.star_generator import StarDraftOutput, StarField, StarGenerator

client = TestClient(app)
SHA = "a" * 40
TOKEN = "gho_" + "t" * 30
HEADERS = {"X-GitHub-Token": TOKEN}


def _diff_body(**extra) -> dict:
    return {
        "user_repository_id": 7,
        "repository": {"github_repo_id": 1, "owner_login": "o", "name": "r", "default_branch": "main"},
        "github_pr_number": 12,
        "title": "JWT 로그인 구현",
        **extra,
    }


def _star_body() -> dict:
    return {
        "candidate_id": "c1",
        "target_login": "octo",
        "title": "JWT 로그인 구현",
        "source_type": "PR",
        "evidence": [{"sha": SHA, "message": "feat: jwt", "churn": "+1/-0", "summary": "요약"}],
    }


class _Port:
    def __init__(self, error: Exception | None = None, partial: bool = False) -> None:
        self.error = error
        self.partial = partial
        self.tokens: list[str] = []

    async def fetch_candidate_details(self, request, github_token):
        self.tokens.append(github_token)
        if self.error:
            raise self.error
        return RepositoryActivityExecution(
            result=CandidateDetailResult(
                user_repository_id=request.user_repository_id,
                github_pr_number=request.github_pr_number,
                commits=[{
                    "sha": SHA, "message": "feat: jwt 필터", "author_login": "octo",
                    "authored_at": datetime(2026, 9, 1, tzinfo=timezone.utc), "additions": 3, "deletions": 1,
                    "files": [{"path": "Filter.java", "status": "ADDED", "additions": 3, "deletions": 1, "patch": "+x"}],
                }],
                partial=self.partial,
                partial_reason="CAP_EXCEEDED" if self.partial else None,
            ),
            api_calls=4,
        )


class _LLM:
    def __init__(self, response) -> None:
        self.response = response

    def call(self, system_prompt, payload, output_model, max_completion_tokens):
        if isinstance(self.response, Exception):
            raise self.response
        return StructuredCallResult(output=self.response, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _pipeline(diff_response=None, star_response=None) -> AnalysisPipeline:
    pipeline = AnalysisPipeline()
    pipeline.diff_analyzer = DiffAnalyzer(_LLM(diff_response or DiffSummaryOutput(
        commits=[CommitSummary(sha=SHA, summary="JWT 필터를 추가했다.", technical_points=["OncePerRequestFilter"])]
    )))
    empty = StarField(status="EMPTY", text=None, evidence_shas=[], insufficient_reason="근거 없음")
    pipeline.star_generator = StarGenerator(_LLM(star_response or StarDraftOutput(
        S=empty, T=empty, R=empty,
        A=StarField(status="FILLED", text="JWT 필터를 추가했다.", evidence_shas=[SHA], insufficient_reason=None),
    )))
    return pipeline


@pytest.fixture
def override():
    def apply(pipeline: AnalysisPipeline, port: _Port) -> _Port:
        app.dependency_overrides[get_analysis_pipeline] = lambda: pipeline
        app.dependency_overrides[get_repository_activity_port] = lambda: port
        return port

    yield apply
    app.dependency_overrides.clear()


def test_diff_evidence_returns_evidence_without_patch(override) -> None:
    port = override(_pipeline(), _Port(partial=True))

    response = client.post("/internal/analysis/diff-evidence", json=_diff_body(), headers=HEADERS)

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    data = body["data"]
    assert data["user_repository_id"] == 7
    assert data["github_pr_number"] == 12
    assert (data["partial"], data["partial_reason"]) == (True, "CAP_EXCEEDED")
    assert data["evidence"][0] == {
        "sha": SHA, "message": "feat: jwt 필터", "author_login": "octo", "churn": "+3/-1",
        "summary": "JWT 필터를 추가했다.", "summary_source": "DIFF",
        "technical_points": ["OncePerRequestFilter"], "url": None,
    }
    assert body["meta"]["tool_calls_made"] == 4
    assert port.tokens == [TOKEN]
    assert TOKEN not in response.text
    assert "patch" not in response.text


def test_diff_evidence_output_is_valid_star_input(override) -> None:
    override(_pipeline(), _Port())
    evidence = client.post("/internal/analysis/diff-evidence", json=_diff_body(), headers=HEADERS).json()["data"]["evidence"]

    response = client.post("/internal/analysis/star", json={**_star_body(), "evidence": evidence})

    assert response.status_code == 200
    star = response.json()["data"]["star"]
    assert star["A"]["status"] == "FILLED"
    assert response.json()["data"]["missing_fields"] == ["S", "T", "R"]


@pytest.mark.parametrize(
    ("error", "status", "code", "retryable"),
    [
        (GithubResourceNotFound("x"), 404, "RESOURCE_NOT_FOUND", False),
        (GithubApiError("x"), 502, "GITHUB_API_ERROR", True),
    ],
)
def test_diff_evidence_maps_github_errors(override, error, status, code, retryable) -> None:
    override(_pipeline(), _Port(error=error))

    response = client.post("/internal/analysis/diff-evidence", json=_diff_body(), headers=HEADERS)

    assert response.status_code == status
    assert response.json()["error"] == {"code": code, "message": response.json()["error"]["message"], "retryable": retryable}


@pytest.mark.parametrize(
    ("llm_error", "retryable"),
    [(LLMConfigError("GITORY_LLM_API_KEY 없음"), False), (LLMStructuredCallError("rate limit"), True)],
)
def test_diff_evidence_maps_llm_errors_to_503(override, llm_error, retryable) -> None:
    override(_pipeline(diff_response=llm_error), _Port())

    response = client.post("/internal/analysis/diff-evidence", json=_diff_body(), headers=HEADERS)

    assert response.status_code == 503
    error = response.json()["error"]
    assert (error["code"], error["retryable"]) == ("LLM_UNAVAILABLE", retryable)
    assert "GITORY_LLM_API_KEY" not in error["message"]


@pytest.mark.parametrize(
    ("llm_error", "retryable"),
    [(LLMConfigError("missing"), False), (LLMStructuredCallError("rate limit"), True)],
)
def test_star_maps_llm_errors_to_503(override, llm_error, retryable) -> None:
    override(_pipeline(star_response=llm_error), _Port())

    response = client.post("/internal/analysis/star", json=_star_body())

    assert response.status_code == 503
    assert (response.json()["error"]["code"], response.json()["error"]["retryable"]) == ("LLM_UNAVAILABLE", retryable)


def test_diff_evidence_requires_token_and_title(override) -> None:
    override(_pipeline(), _Port())

    assert client.post("/internal/analysis/diff-evidence", json=_diff_body()).status_code == 400
    body = _diff_body()
    del body["title"]
    assert client.post("/internal/analysis/diff-evidence", json=body, headers=HEADERS).status_code == 400


def test_diff_evidence_requires_candidate_reference(override) -> None:
    override(_pipeline(), _Port())

    response = client.post(
        "/internal/analysis/diff-evidence", json=_diff_body(github_pr_number=None), headers=HEADERS
    )

    assert response.status_code == 400
