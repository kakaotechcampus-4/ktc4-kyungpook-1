"""운영 문서 on/off, 실제 오류 형식과 오프라인 OpenAPI export를 검증한다."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys

from fastapi.testclient import TestClient
import pytest

from api.collection import get_repository_activity_port
from main import create_app
from schemas.common import ErrorEnvelope
from services.github_collector import GithubApiError, GithubResourceNotFound

INTERNAL_PATHS = {
    "/internal/collect",
    "/internal/collect/candidate-details",
    "/internal/analysis/groups",
    "/internal/analysis/diff-evidence",
    "/internal/analysis/star",
    "/internal/interview-turns",
    "/internal/interview-answer-evaluations",
}


def test_health_is_explicitly_process_liveness_not_analysis_readiness() -> None:
    application = create_app(docs_enabled=True)
    response = TestClient(application).get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok", "service": "gitory-ai", "version": "0.0.1"
    }
    operation = application.openapi()["paths"]["/health"]["get"]
    assert "준비 완료를 보장하지 않습니다" in operation["description"]


def test_openapi_documents_actual_validation_and_collection_errors() -> None:
    application = create_app(docs_enabled=True)
    document = TestClient(application).get("/openapi.json").json()

    assert document["info"]["version"] == "0.0.1"
    assert set(document["paths"]) == INTERNAL_PATHS | {"/health"}
    assert "/matching" not in document["paths"]
    for path in INTERNAL_PATHS:
        operation = document["paths"][path]["post"]
        assert operation["summary"]
        assert operation["description"]
        assert "400" in operation["responses"]
        assert "422" not in operation["responses"]
        error_schema = operation["responses"]["400"]["content"]["application/json"]["schema"]
        assert error_schema["$ref"].endswith("/ErrorEnvelope")

    for path in ("/internal/collect", "/internal/collect/candidate-details", "/internal/analysis/diff-evidence"):
        operation = document["paths"][path]["post"]
        assert {"404", "502"}.issubset(operation["responses"])
        header = next(p for p in operation["parameters"] if p["name"] == "X-GitHub-Token")
        assert header["in"] == "header"
        assert header["required"] is True

    for path in ("/internal/analysis/groups", "/internal/analysis/diff-evidence", "/internal/analysis/star"):
        operation = document["paths"][path]["post"]
        assert "미구현" not in operation["summary"]
        assert "LLM_UNAVAILABLE" in operation["responses"]["503"]["description"]


@pytest.mark.parametrize("setting", ["false", "0", "off"])
def test_document_routes_can_be_disabled_without_disabling_api(monkeypatch, setting) -> None:
    monkeypatch.setenv("AI_DOCS_ENABLED", setting)
    client = TestClient(create_app())

    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404
    assert client.get("/health").status_code == 200
    response = client.post("/internal/collect", json={})
    assert response.status_code == 400
    ErrorEnvelope.model_validate(response.json())


def test_document_routes_are_enabled_for_local_development(monkeypatch) -> None:
    monkeypatch.delenv("AI_DOCS_ENABLED", raising=False)
    client = TestClient(create_app())

    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 200


@pytest.mark.parametrize(
    ("failure", "status", "code", "retryable"),
    [
        (GithubResourceNotFound("not found"), 404, "RESOURCE_NOT_FOUND", False),
        (GithubApiError("upstream failed"), 502, "GITHUB_API_ERROR", True),
    ],
)
@pytest.mark.parametrize("detail", [False, True])
def test_documented_collection_failure_envelopes_match_runtime(
    failure, status, code, retryable, detail
) -> None:
    class FailingPort:
        async def collect(self, request, github_token):
            raise failure

        async def fetch_candidate_details(self, request, github_token):
            raise failure

    application = create_app(docs_enabled=True)
    application.dependency_overrides[get_repository_activity_port] = FailingPort
    payload = {
        "user_repository_id": 123,
        "repository": {
            "github_repo_id": 456789, "owner_login": "example", "name": "gitory",
            "default_branch": "develop",
        },
    }
    if detail:
        payload["commit_shas"] = ["abc1234567890"]
        path = "/internal/collect/candidate-details"
    else:
        payload["actor"] = {"github_login": "example", "known_emails": []}
        path = "/internal/collect"
    response = TestClient(application).post(
        path, json=payload, headers={"X-GitHub-Token": "test-only-secret"}
    )

    assert response.status_code == status
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == code
    assert envelope.error.retryable is retryable
    assert "test-only-secret" not in response.text


def test_empty_grouping_request_returns_empty_without_calling_llm() -> None:
    response = TestClient(create_app(), raise_server_exceptions=False).post(
        "/internal/analysis/groups",
        json={"repository_id": "repo-1", "target_login": "example", "commits": []},
    )

    assert response.status_code == 200
    assert response.json()["data"] == {
        "verdict": "EMPTY",
        "candidates": [],
        "excluded_commit_shas": [],
    }
    assert response.json()["success"] is True


def test_offline_export_matches_running_schema_when_docs_are_disabled(tmp_path) -> None:
    script = Path(__file__).resolve().parents[1] / "scripts" / "export_openapi.py"
    output = tmp_path / "nested" / "ai.json"
    subprocess.run(
        [sys.executable, str(script), "--output", str(output)],
        cwd=tmp_path,
        env={**os.environ, "AI_DOCS_ENABLED": "false"},
        check=True, capture_output=True, text=True,
    )

    assert json.loads(output.read_text(encoding="utf-8")) == create_app(docs_enabled=True).openapi()
