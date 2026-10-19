"""기업·직무 공고 RAG 계약과 서비스 테스트."""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from openai import (
    APIConnectionError,
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    PermissionDeniedError,
    RateLimitError,
)
from pydantic import ValidationError

from api.matching import get_talent_matcher_factory
from main import app
from rag.embedding import (
    ChromaDefaultTextEmbedder,
    EmbeddingError,
    OpenAITextEmbedder,
    create_text_embedder,
)
from rag.vector_store import ChromaJobCriterionStore
from schemas.common import ErrorEnvelope
from schemas.matching import (
    JobPosting,
    JobPostingIndexRequest,
    JobPostingIndexResult,
    MatchingRequest,
    MatchingResponse,
)
from services.talent_matcher import TalentMatcher
from scripts.evaluate_job_matching import score_rankings


class FakeEmbedder:
    """외부 모델 없이 입력 개수와 호출 내용을 확인하는 임베더."""

    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(texts)
        return [[float(index), 1.0] for index, _ in enumerate(texts)]


class FakeStore:
    """공고 교체·삭제·검색을 관찰하는 벡터 저장소 대역."""

    def __init__(self) -> None:
        self.replaced: list[dict[str, Any]] = []
        self.deleted: list[str] = []
        self.hits: list[dict[str, Any]] = []

    def replace_posting(self, posting_key, ids, embeddings, documents, metadatas):
        self.replaced.append(
            {
                "posting_key": posting_key,
                "ids": ids,
                "embeddings": embeddings,
                "documents": documents,
                "metadatas": metadatas,
            }
        )

    def delete_posting(self, posting_key):
        self.deleted.append(posting_key)

    def query(self, embedding, top_k):
        return self.hits[:top_k]


def posting_payload(**overrides) -> dict:
    payload = {
        "source": "FIXTURE",
        "external_posting_id": "backend-1",
        "company_name": "예시회사",
        "job_title": "백엔드 개발자",
        "job_category": "BACKEND",
        "responsibilities": ["Spring Boot 기반 API 개발", "Spring Boot 기반 API 개발"],
        "required_qualifications": ["REST API 설계 경험"],
        "preferred_qualifications": ["성능 개선 경험"],
        "skills": ["Java"],
        "source_url": "https://example.com/jobs/backend-1",
        "collected_at": "2026-10-11T00:00:00Z",
        "expires_at": "2026-10-31T23:59:59Z",
        "status": "ACTIVE",
        "content_hash": "12345678abcdef",
    }
    payload.update(overrides)
    return payload


def matching_payload() -> dict:
    return {
        "statements": [
            {
                "card_id": 11,
                "star_slot": "A",
                "statement_seq": 1,
                "body": "Spring Boot API의 응답 시간을 개선했습니다.",
            }
        ],
        "job_categories": ["BACKEND"],
        "top_k": 5,
    }


def test_active_posting_requires_at_least_one_matchable_criterion() -> None:
    with pytest.raises(ValidationError):
        JobPosting.model_validate(
            posting_payload(
                responsibilities=[],
                required_qualifications=[],
                preferred_qualifications=[],
                skills=[],
            )
        )


def test_matching_request_rejects_unknown_and_unconfirmed_shape() -> None:
    payload = matching_payload()
    payload["candidate_id"] = "old-contract"

    with pytest.raises(ValidationError):
        MatchingRequest.model_validate(payload)


def test_local_embedding_is_the_default_without_api_key(monkeypatch) -> None:
    monkeypatch.delenv("GITORY_EMBEDDING_PROVIDER", raising=False)
    monkeypatch.delenv("GITORY_EMBEDDING_API_KEY", raising=False)

    embedder = create_text_embedder()

    assert isinstance(embedder, ChromaDefaultTextEmbedder)


def test_index_splits_criteria_and_removes_duplicates() -> None:
    embedder = FakeEmbedder()
    store = FakeStore()
    matcher = TalentMatcher(embedder, store)

    result = matcher.index(
        JobPostingIndexRequest(postings=[JobPosting.model_validate(posting_payload())])
    )

    assert result.indexed_postings == 1
    assert result.indexed_criteria == 4
    assert embedder.calls == [[
        "Spring Boot 기반 API 개발",
        "REST API 설계 경험",
        "성능 개선 경험",
        "Java",
    ]]
    assert store.replaced[0]["posting_key"] == "FIXTURE:backend-1"
    assert all(
        metadata["content_hash"] == "12345678abcdef"
        for metadata in store.replaced[0]["metadatas"]
    )


def test_index_keeps_the_type_of_each_extracted_criterion() -> None:
    criteria = [
        {"criterion_type": kind, "text": f"{kind} 조건", "source_quote": f"{kind} 조건"}
        for kind in ("RESPONSIBILITY", "REQUIRED", "PREFERRED")
    ]
    payload = posting_payload(
        criteria=criteria,
        responsibilities=[],
        required_qualifications=[],
        preferred_qualifications=[],
        skills=[],
    )
    store = FakeStore()

    TalentMatcher(FakeEmbedder(), store).index(
        JobPostingIndexRequest(postings=[JobPosting.model_validate(payload)])
    )

    assert [metadata["criterion_type"] for metadata in store.replaced[0]["metadatas"]] == [
        "RESPONSIBILITY",
        "REQUIRED",
        "PREFERRED",
    ]


def test_inactive_posting_is_removed_without_embedding() -> None:
    embedder = FakeEmbedder()
    store = FakeStore()
    matcher = TalentMatcher(embedder, store)
    inactive = JobPosting.model_validate(
        posting_payload(status="EXPIRED", responsibilities=[], required_qualifications=[])
    )

    result = matcher.index(JobPostingIndexRequest(postings=[inactive]))

    assert result.deactivated_postings == 1
    assert store.deleted == ["FIXTURE:backend-1"]
    assert embedder.calls == []


def test_metadata_only_posting_is_excluded_from_precise_matching() -> None:
    embedder = FakeEmbedder()
    store = FakeStore()
    matcher = TalentMatcher(embedder, store)
    metadata_only = JobPosting.model_validate(
        posting_payload(
            content_status="METADATA_ONLY",
            responsibilities=[],
            required_qualifications=[],
            preferred_qualifications=[],
            skills=[],
        )
    )

    result = matcher.index(JobPostingIndexRequest(postings=[metadata_only]))

    assert result.skipped_metadata_only == 1
    assert store.deleted == ["FIXTURE:backend-1"]
    assert embedder.calls == []


def test_match_returns_source_and_exact_star_evidence_without_storing_star() -> None:
    embedder = FakeEmbedder()
    store = FakeStore()
    store.hits = [
        {
            "document": "Spring Boot 기반 API 개발",
            "distance": 0.12,
            "metadata": {
                "posting_key": "FIXTURE:backend-1",
                "company_name": "예시회사",
                "job_title": "백엔드 개발자",
                "job_category": "BACKEND",
                "source_url": "https://example.com/jobs/backend-1",
                "criterion_type": "RESPONSIBILITY",
            },
        },
        {
            "document": "디자인 시스템 운영",
            "distance": 0.1,
            "metadata": {
                "posting_key": "FIXTURE:frontend-1",
                "company_name": "다른회사",
                "job_title": "프론트엔드 개발자",
                "job_category": "FRONTEND",
                "source_url": "https://example.com/jobs/frontend-1",
                "criterion_type": "RESPONSIBILITY",
            },
        },
        {
            "document": "무관한 먼 조건",
            "distance": 0.9,
            "metadata": {
                "posting_key": "FIXTURE:backend-2",
                "company_name": "먼회사",
                "job_title": "백엔드 개발자",
                "job_category": "BACKEND",
                "source_url": "https://example.com/jobs/backend-2",
                "criterion_type": "REQUIRED",
            },
        },
    ]
    matcher = TalentMatcher(embedder, store, maximum_distance=0.4)

    result = matcher.match(MatchingRequest.model_validate(matching_payload()))

    assert len(result.matches) == 1
    match = result.matches[0]
    assert match.posting_key == "FIXTURE:backend-1"
    assert match.evidence[0].card_id == 11
    assert match.evidence[0].star_slot == "A"


def test_matching_routes_use_common_envelope() -> None:
    matcher = TalentMatcher(FakeEmbedder(), FakeStore())
    app.dependency_overrides[get_talent_matcher_factory] = lambda: lambda: matcher
    try:
        response = TestClient(app).post("/internal/matching/search", json=matching_payload())
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["success"] is True
    assert response.json()["data"] == {"matches": []}
    paths = set(app.openapi()["paths"])
    assert "/internal/matching/postings/index" in paths
    assert "/internal/matching/search" in paths


def test_matching_api_returns_400_for_contract_violation() -> None:
    payload = matching_payload()
    payload["statements"][0]["star_slot"] = "X"

    matcher = TalentMatcher(FakeEmbedder(), FakeStore())
    app.dependency_overrides[get_talent_matcher_factory] = lambda: lambda: matcher
    try:
        response = TestClient(app).post("/internal/matching/search", json=payload)
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_chroma_store_replaces_and_deletes_one_posting(tmp_path) -> None:
    """실제 Chroma 어댑터가 공고별 교체·삭제 경계를 지키는지 확인한다."""
    store = ChromaJobCriterionStore(persist_directory=str(tmp_path / "chroma"))
    metadata = {
        "posting_key": "FIXTURE:backend-1",
        "company_name": "예시회사",
        "job_title": "백엔드 개발자",
        "job_category": "BACKEND",
        "source_url": "https://example.com/jobs/backend-1",
        "criterion_type": "REQUIRED",
        "content_hash": "12345678abcdef",
    }
    store.replace_posting(
        "FIXTURE:backend-1",
        ["criterion-1"],
        [[1.0, 0.0]],
        ["REST API 설계 경험"],
        [metadata],
    )

    hits = store.query([1.0, 0.0], top_k=5)
    assert hits[0]["document"] == "REST API 설계 경험"

    store.delete_posting("FIXTURE:backend-1")
    assert store.query([1.0, 0.0], top_k=5) == []


def test_job_matching_eval_fixture_has_30_valid_unique_postings() -> None:
    fixture_path = Path(__file__).parents[1] / "evals" / "job_matching_cases.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))

    request = JobPostingIndexRequest.model_validate({"postings": fixture["postings"]})
    posting_keys = {posting.posting_key for posting in request.postings}
    expected_keys = {case["expected_posting_key"] for case in fixture["cases"]}

    assert len(request.postings) == 30
    assert len(posting_keys) == 30
    assert len(fixture["cases"]) == 30
    assert expected_keys == posting_keys
    assert all(posting.source == "EVAL_FIXTURE" for posting in request.postings)
    assert all(str(posting.source_url).startswith("https://example.com/") for posting in request.postings)


def test_ranking_metrics_count_missing_expected_posting_as_zero() -> None:
    metrics = score_rankings(
        [
            {"rank": 1},
            {"rank": 3},
            {"rank": None},
        ]
    )

    assert metrics["recall_at_1"] == pytest.approx(1 / 3)
    assert metrics["recall_at_3"] == pytest.approx(2 / 3)
    assert metrics["recall_at_5"] == pytest.approx(2 / 3)
    assert metrics["mrr"] == pytest.approx((1 + 1 / 3) / 3)


def test_matching_contract_examples_validate_against_ai_dtos() -> None:
    contract_path = Path(__file__).parents[2] / "contracts" / "matching" / "job-matching.json"
    contract = json.loads(contract_path.read_text(encoding="utf-8"))

    index = contract["index"]
    search = contract["search"]
    JobPostingIndexRequest.model_validate(index["spring_to_ai"])
    JobPostingIndexResult.model_validate(index["ai_to_spring"])
    MatchingRequest.model_validate(search["spring_to_ai"])
    MatchingResponse.model_validate(search["ai_to_spring"])


def test_match_response_carries_no_grade() -> None:
    store = FakeStore()
    store.hits = [
        {
            "document": "Spring Boot 기반 API 개발",
            "distance": 0.12,
            "metadata": {
                "posting_key": "FIXTURE:backend-1",
                "company_name": "예시회사",
                "job_title": "백엔드 개발자",
                "job_category": "BACKEND",
                "source_url": "https://example.com/jobs/backend-1",
                "criterion_type": "RESPONSIBILITY",
            },
        }
    ]
    matcher = TalentMatcher(FakeEmbedder(), store)

    result = matcher.match(MatchingRequest.model_validate(matching_payload()))

    assert "fit_grade" not in result.matches[0].model_dump()


class ThreadRecordingEmbedder:
    def __init__(self) -> None:
        self.thread_names: list[str] = []

    def embed(self, texts):
        self.thread_names.append(threading.current_thread().name)
        return [[1.0, 0.0] for _ in texts]


class RaisingEmbedder:
    def __init__(self, failure: Exception) -> None:
        self._failure = failure

    def embed(self, texts):
        raise self._failure


def post_matching(path: str, payload: dict, matcher: TalentMatcher | None = None):
    if matcher is not None:
        app.dependency_overrides[get_talent_matcher_factory] = lambda: lambda: matcher
    try:
        return TestClient(app).post(path, json=payload)
    finally:
        app.dependency_overrides.clear()


INDEX_PATH = "/internal/matching/postings/index"
SEARCH_PATH = "/internal/matching/search"


def index_payload() -> dict:
    return {"postings": [posting_payload()]}


@pytest.mark.parametrize(
    ("path", "payload"),
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, matching_payload())],
)
def test_embedding_runs_outside_the_event_loop(path, payload) -> None:
    embedder = ThreadRecordingEmbedder()

    response = post_matching(path, payload, TalentMatcher(embedder, FakeStore()))

    assert response.status_code == 200
    assert embedder.thread_names
    assert all(name.startswith("matching-index") for name in embedder.thread_names)


@pytest.mark.parametrize(
    ("path", "payload"),
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, matching_payload())],
)
@pytest.mark.parametrize("retryable", [True, False])
def test_embedding_failure_uses_error_envelope_with_its_retryable(path, payload, retryable) -> None:
    failure = EmbeddingError("embedding down", retryable=retryable)

    response = post_matching(path, payload, TalentMatcher(RaisingEmbedder(failure), FakeStore()))

    assert response.status_code == 503
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == "LLM_UNAVAILABLE"
    assert envelope.error.retryable is retryable


@pytest.mark.parametrize(
    ("path", "payload"),
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, matching_payload())],
)
@pytest.mark.parametrize("provider", ["openai", "unknown-provider"])
def test_embedding_settings_error_returns_envelope_instead_of_bare_500(
    monkeypatch, path, payload, provider
) -> None:
    monkeypatch.setenv("GITORY_EMBEDDING_PROVIDER", provider)
    monkeypatch.delenv("GITORY_EMBEDDING_API_KEY", raising=False)

    response = post_matching(path, payload)

    assert response.status_code == 503
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == "LLM_UNAVAILABLE"
    assert envelope.error.retryable is False


@pytest.mark.parametrize(
    ("path", "payload"),
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, matching_payload())],
)
def test_unexpected_failure_returns_internal_error_without_leaking_detail(path, payload) -> None:
    failure = RuntimeError("secret internal detail")

    response = post_matching(path, payload, TalentMatcher(RaisingEmbedder(failure), FakeStore()))

    assert response.status_code == 500
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == "INTERNAL_ERROR"
    assert envelope.error.retryable is False
    assert "secret internal detail" not in response.text


def _http_response(status: int) -> httpx.Response:
    return httpx.Response(status, request=httpx.Request("POST", "https://embeddings.example.com"))


@pytest.mark.parametrize(
    ("failure", "retryable"),
    [
        (AuthenticationError("bad key", response=_http_response(401), body=None), False),
        (PermissionDeniedError("no access", response=_http_response(403), body=None), False),
        (NotFoundError("no model", response=_http_response(404), body=None), False),
        (BadRequestError("bad input", response=_http_response(400), body=None), False),
        (RateLimitError("slow down", response=_http_response(429), body=None), True),
        (
            RateLimitError(
                "quota",
                response=_http_response(429),
                body={"code": "insufficient_quota", "message": "quota"},
            ),
            False,
        ),
        (APIConnectionError(request=httpx.Request("POST", "https://embeddings.example.com")), True),
    ],
)
def test_openai_embedder_marks_only_permanent_failures_as_not_retryable(
    monkeypatch, failure, retryable
) -> None:
    monkeypatch.setenv("GITORY_EMBEDDING_API_KEY", "test-only-key")
    embedder = OpenAITextEmbedder()

    def fail(**kwargs):
        raise failure

    monkeypatch.setattr(embedder._client.embeddings, "create", fail)

    with pytest.raises(EmbeddingError) as raised:
        embedder.embed(["Java"])

    assert raised.value.retryable is retryable


def test_openai_embedder_retries_when_response_count_differs_from_input(monkeypatch) -> None:
    monkeypatch.setenv("GITORY_EMBEDDING_API_KEY", "test-only-key")
    embedder = OpenAITextEmbedder()
    monkeypatch.setattr(
        embedder._client.embeddings,
        "create",
        lambda **kwargs: type("R", (), {"data": [type("I", (), {"index": 0, "embedding": [0.1]})()]})(),
    )

    with pytest.raises(EmbeddingError) as raised:
        embedder.embed(["Java", "Spring"])

    assert raised.value.retryable is True
