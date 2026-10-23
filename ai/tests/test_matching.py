"""기업·직무 조건 색인·검색 계약과 서비스 테스트."""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any, Optional
from unittest.mock import patch

import httpx
import numpy as np
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

import api.matching as matching_api
from api.matching import get_talent_matcher_factory
from main import app
from rag.embedding import (
    ChromaDefaultTextEmbedder,
    EmbeddingError,
    OpenAITextEmbedder,
    create_text_embedder,
)
from rag.vector_store import ChromaJobCriterionStore, collection_name_for
from schemas.common import ErrorEnvelope
from schemas.matching import (
    JobPostingIndexRequest,
    JobPostingIndexResult,
    JobPostingPruneRequest,
    JobPostingPruneResult,
    MatchingRequest,
    MatchingResponse,
)
from scripts.evaluate_job_matching import fixture_to_index_request, score_rankings
from services.talent_matcher import TalentMatcher


class DictEmbedder:
    """문장마다 정해 둔 벡터를 돌려주는 결정적 임베더.

    실제 모델을 내려받지 않는다.
    """

    def __init__(self, vectors: Optional[dict[str, list[float]]] = None) -> None:
        self.vectors = vectors or {}
        self.calls: list[list[str]] = []

    def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(texts)
        return [self.vectors.get(text, [1.0, 0.0]) for text in texts]


class InMemoryStore:
    """서비스 규칙만 확인하는 벡터 저장소 대역."""

    def __init__(self) -> None:
        self.vectors: dict[int, tuple[int, list[float]]] = {}

    def replace_posting(self, posting_id, criterion_ids, embeddings):
        self.delete_posting(posting_id)
        for criterion_id, embedding in zip(criterion_ids, embeddings):
            self.vectors[criterion_id] = (posting_id, embedding)

    def delete_posting(self, posting_id):
        self.vectors = {k: v for k, v in self.vectors.items() if v[0] != posting_id}

    def keep_only(self, posting_ids):
        stored = {posting for posting, _ in self.vectors.values()}
        self.vectors = {k: v for k, v in self.vectors.items() if v[0] in posting_ids}
        return len(stored & posting_ids), len(stored - posting_ids)

    def count(self):
        return len(self.vectors)

    def read_vectors(self):
        ids = list(self.vectors)
        return ids, np.asarray([self.vectors[i][1] for i in ids], dtype=np.float64)


def criterion(criterion_id: Any, text: str = "Java 개발 경험", kind: str = "REQUIRED") -> dict:
    return {"criterion_id": criterion_id, "criterion_type": kind, "text": text}


def posting(posting_id: Any, criteria: list[dict], status: str = "ACTIVE") -> dict:
    return {"posting_id": posting_id, "status": status, "criteria": criteria}


def index_request(*postings: dict) -> JobPostingIndexRequest:
    return JobPostingIndexRequest.model_validate({"postings": list(postings)})


def search_request(*bodies: str) -> MatchingRequest:
    return MatchingRequest.model_validate(
        {
            "statements": [
                {"statement_id": index, "body": body} for index, body in enumerate(bodies, start=1)
            ]
        }
    )


@pytest.fixture(autouse=True)
def fresh_shared_matcher(monkeypatch):
    """다른 시험에서 만든 공유 검색기가 설정 오류 시험에 새어 들어가지 않게 한다."""
    monkeypatch.setattr(matching_api, "_shared_matcher", None)


@pytest.mark.parametrize(
    ("path", "payload"),
    [
        (
            "/internal/matching/search",
            {"statements": [{"statement_id": 1, "body": "문장"}], "top_k": 5},
        ),
        (
            "/internal/matching/search",
            {"statements": [{"statement_id": 1, "body": "문장"}], "job_categories": ["BACKEND"]},
        ),
        (
            "/internal/matching/search",
            {"statements": [{"card_id": 11, "star_slot": "A", "statement_seq": 1, "body": "문장"}]},
        ),
        (
            "/internal/matching/postings/index",
            {"postings": [{"posting_key": "FIXTURE:1", "status": "ACTIVE", "criteria": []}]},
        ),
        (
            "/internal/matching/postings/index",
            {"postings": [posting(1, [criterion(1, kind="SKILL")])]},
        ),
        (
            "/internal/matching/postings/index",
            {"postings": [posting(1, [criterion(1)])], "replace_all": True},
        ),
    ],
)
def test_old_contract_fields_are_rejected(path, payload) -> None:
    response = TestClient(app).post(path, json=payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


@pytest.mark.parametrize(
    "payload",
    [
        {"postings": [posting(0, [])]},
        {"postings": [posting(1, [criterion(0)])]},
        {"postings": [posting("1", [])]},
        {"postings": [posting(1, [criterion("1")])]},
        {"postings": [posting(1, [], status="INACTIVE")]},
    ],
)
def test_index_rejects_invalid_ids_and_unknown_status(payload) -> None:
    response = TestClient(app).post("/internal/matching/postings/index", json=payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


@pytest.mark.parametrize(
    "payload",
    [
        {"keep_posting_ids": []},
        {"keep_posting_ids": [1, 1]},
        {"keep_posting_ids": [0]},
        {"keep_posting_ids": ["1"]},
        {"keep_posting_ids": list(range(1, 10_002))},
        {"keep_posting_ids": [1], "posting_ids": [2]},
    ],
)
def test_prune_rejects_invalid_keep_lists(payload) -> None:
    response = TestClient(app).post(PRUNE_PATH, json=payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_prune_accepts_the_maximum_keep_list() -> None:
    assert JobPostingPruneRequest(keep_posting_ids=list(range(1, 10_001)))


@pytest.mark.parametrize(
    "statements",
    [
        [{"statement_id": 0, "body": "문장"}],
        [{"statement_id": "1", "body": "문장"}],
    ],
)
def test_search_rejects_invalid_statement_ids(statements) -> None:
    response = TestClient(app).post("/internal/matching/search", json={"statements": statements})

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


@pytest.mark.parametrize(
    "request_model",
    [
        lambda: index_request(posting(1, [criterion(1)]), posting(1, [criterion(2)])),
        lambda: index_request(posting(1, [criterion(1)]), posting(2, [criterion(1)])),
        lambda: MatchingRequest.model_validate(
            {"statements": [{"statement_id": 1, "body": "가"}, {"statement_id": 1, "body": "나"}]}
        ),
    ],
)
def test_ids_repeated_in_one_request_are_rejected(request_model) -> None:
    with pytest.raises(ValidationError):
        request_model()


def test_search_rejects_more_than_200_statements() -> None:
    statements = [{"statement_id": index, "body": "문장"} for index in range(1, 202)]

    response = TestClient(app).post("/internal/matching/search", json={"statements": statements})

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_local_embedding_is_the_default_without_api_key(monkeypatch) -> None:
    monkeypatch.delenv("GITORY_EMBEDDING_PROVIDER", raising=False)
    monkeypatch.delenv("GITORY_EMBEDDING_API_KEY", raising=False)

    embedder = create_text_embedder()

    assert isinstance(embedder, ChromaDefaultTextEmbedder)


def test_index_stores_vectors_by_criterion_id_and_overwrites_a_resent_posting(tmp_path) -> None:
    store = ChromaJobCriterionStore(model_name="test-model", persist_directory=str(tmp_path))
    matcher = TalentMatcher(DictEmbedder(), store)

    matcher.index(index_request(posting(7, [criterion(10), criterion(11)])))
    result = matcher.index(index_request(posting(7, [criterion(10), criterion(12)])))

    ids, _ = store.read_vectors()
    assert sorted(ids) == [10, 12]
    assert (result.indexed_postings, result.indexed_criteria) == (1, 2)


def test_active_posting_with_empty_criteria_removes_its_vectors() -> None:
    store = InMemoryStore()
    matcher = TalentMatcher(DictEmbedder(), store)
    matcher.index(index_request(posting(1, [criterion(10)]), posting(2, [criterion(20)])))

    result = matcher.index(index_request(posting(1, [])))

    assert set(store.vectors) == {20}
    assert result.deactivated_postings == 1


@pytest.mark.parametrize("status", ["EXPIRED", "REMOVED"])
def test_expired_or_removed_posting_is_removed_without_embedding(status) -> None:
    store = InMemoryStore()
    embedder = DictEmbedder()
    matcher = TalentMatcher(embedder, store)
    matcher.index(index_request(posting(1, [criterion(10)])))
    embedder.calls.clear()

    result = matcher.index(index_request(posting(1, [criterion(10)], status=status)))

    assert store.count() == 0
    assert embedder.calls == []
    assert result.deactivated_postings == 1


def test_prune_removes_only_postings_missing_from_the_keep_list(tmp_path) -> None:
    store = ChromaJobCriterionStore(model_name="test-model", persist_directory=str(tmp_path))
    matcher = TalentMatcher(DictEmbedder(), store)
    matcher.index(
        index_request(
            posting(1, [criterion(10), criterion(11)]),
            posting(2, [criterion(20), criterion(21)]),
            posting(3, [criterion(30)]),
        )
    )

    result = matcher.prune(JobPostingPruneRequest(keep_posting_ids=[1, 3, 99]))

    ids, _ = store.read_vectors()
    assert sorted(ids) == [10, 11, 30]
    assert result == JobPostingPruneResult(kept_postings=2, removed_postings=1)


def test_search_returns_every_criterion_within_distance_without_a_cap(tmp_path) -> None:
    store = ChromaJobCriterionStore(model_name="test-model", persist_directory=str(tmp_path))
    texts = {f"조건 {i}": [1.0, i * 0.0001] for i in range(300)}
    matcher = TalentMatcher(DictEmbedder({**texts, "문장": [1.0, 0.0]}), store)
    postings = [
        posting(group + 1, [criterion(group * 100 + i + 1, f"조건 {group * 100 + i}") for i in range(100)])
        for group in range(3)
    ]
    matcher.index(index_request(*postings))

    with patch.object(type(store._collection), "query", side_effect=AssertionError("근사 검색 금지")):
        result = matcher.match(search_request("문장"))

    assert len(result.matches) == 300


def test_search_distance_is_never_negative_when_statement_equals_criterion_text() -> None:
    rng = np.random.default_rng(0)
    vectors = {f"글 {i}": rng.normal(size=384).tolist() for i in range(50)}
    store = InMemoryStore()
    matcher = TalentMatcher(DictEmbedder(vectors), store)
    matcher.index(
        index_request(posting(1, [criterion(i + 1, text) for i, text in enumerate(vectors)]))
    )

    result = matcher.match(search_request(*vectors))

    assert len(result.matches) == 50
    assert all(match.distance >= 0 for match in result.matches)


def test_search_returns_both_criteria_of_the_same_posting() -> None:
    store = InMemoryStore()
    matcher = TalentMatcher(DictEmbedder(), store)
    matcher.index(index_request(posting(1, [criterion(10, "가"), criterion(11, "나")])))

    result = matcher.match(search_request("문장"))

    assert {m.criterion_id for m in result.matches} == {10, 11}


def test_search_leaves_out_criteria_beyond_the_distance_limit() -> None:
    store = InMemoryStore()
    embedder = DictEmbedder({"가까운": [1.0, 0.0], "먼": [0.0, 1.0], "문장": [1.0, 0.0]})
    matcher = TalentMatcher(embedder, store, maximum_distance=0.4)
    matcher.index(index_request(posting(1, [criterion(10, "가까운"), criterion(11, "먼")])))

    result = matcher.match(search_request("문장"))

    assert [(m.criterion_id, m.statement_id) for m in result.matches] == [(10, 1)]


def test_search_reports_how_many_criteria_the_collection_holds() -> None:
    store = InMemoryStore()
    embedder = DictEmbedder({"가까운": [1.0, 0.0], "먼": [0.0, 1.0], "문장": [1.0, 0.0]})
    matcher = TalentMatcher(embedder, store, maximum_distance=0.4)
    matcher.index(index_request(posting(1, [criterion(10, "가까운"), criterion(11, "먼")])))

    result = matcher.match(search_request("문장"))

    assert result.collection_criteria == 2


def test_search_on_an_empty_collection_skips_embedding() -> None:
    embedder = DictEmbedder()
    matcher = TalentMatcher(embedder, InMemoryStore())

    result = matcher.match(search_request("문장"))

    assert embedder.calls == []
    assert result == MatchingResponse(matches=[], collection_criteria=0)


def test_search_distance_equals_the_chroma_cosine_distance(tmp_path) -> None:
    store = ChromaJobCriterionStore(model_name="test-model", persist_directory=str(tmp_path))
    embedder = DictEmbedder({"조건": [0.3, 0.9, 0.1], "문장": [0.8, 0.2, 0.5]})
    matcher = TalentMatcher(embedder, store, maximum_distance=2.0)
    matcher.index(index_request(posting(1, [criterion(10, "조건")])))

    ours = matcher.match(search_request("문장")).matches[0].distance
    chroma = store._collection.query(query_embeddings=[[0.8, 0.2, 0.5]], n_results=1)[
        "distances"
    ][0][0]

    assert ours == pytest.approx(chroma, abs=1e-6)


def test_each_embedding_model_has_its_own_collection(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("GITORY_CHROMA_PATH", str(tmp_path))
    monkeypatch.setenv("GITORY_EMBEDDING_PROVIDER", "openai")
    monkeypatch.setenv("GITORY_EMBEDDING_API_KEY", "test-key")
    monkeypatch.setenv("GITORY_EMBEDDING_MODEL", "model-old")
    old = matching_api.build_talent_matcher()._store
    monkeypatch.setenv("GITORY_EMBEDDING_MODEL", "model-new")
    new = matching_api.build_talent_matcher()._store

    old.replace_posting(1, [10], [[1.0, 0.0]])

    assert old._collection.name == collection_name_for("model-old")
    assert (old.count(), new.count()) == (1, 0)


def test_collection_names_differ_for_models_that_would_sanitize_alike() -> None:
    long_prefix = "sentence-transformers/paraphrase-multilingual-MiniLM-L"

    assert collection_name_for("bge.m3") != collection_name_for("bge-m3")
    assert collection_name_for(long_prefix + "12-v2") != collection_name_for(long_prefix + "6-v2")
    assert len(collection_name_for(long_prefix + "12-v2")) <= 63


def test_shared_matcher_is_built_once_and_reused(monkeypatch) -> None:
    built = []

    def build() -> TalentMatcher:
        built.append(1)
        return TalentMatcher(DictEmbedder(), InMemoryStore())

    monkeypatch.setattr(matching_api, "build_talent_matcher", build)

    first = matching_api.shared_talent_matcher()
    second = matching_api.shared_talent_matcher()

    assert first is second
    assert len(built) == 1


def test_failed_matcher_build_is_not_kept_so_a_fixed_setting_works_next_time(monkeypatch) -> None:
    attempts = []

    def build() -> TalentMatcher:
        attempts.append(1)
        if len(attempts) == 1:
            raise EmbeddingError("설정 누락", retryable=False)
        return TalentMatcher(DictEmbedder(), InMemoryStore())

    monkeypatch.setattr(matching_api, "build_talent_matcher", build)

    with pytest.raises(EmbeddingError):
        matching_api.shared_talent_matcher()

    assert isinstance(matching_api.shared_talent_matcher(), TalentMatcher)
    assert len(attempts) == 2


def test_prune_route_returns_counts_in_common_envelope() -> None:
    store = InMemoryStore()
    matcher = TalentMatcher(DictEmbedder(), store)
    matcher.index(index_request(posting(1, [criterion(10)]), posting(2, [criterion(20)])))

    response = post_matching(PRUNE_PATH, {"keep_posting_ids": [1]}, matcher)

    assert response.status_code == 200
    assert response.json()["success"] is True
    assert response.json()["data"] == {"kept_postings": 1, "removed_postings": 1}
    assert response.json()["meta"]["model"] is None
    assert response.json()["meta"]["tool_calls_made"] == 0


def test_matching_routes_use_common_envelope() -> None:
    matcher = TalentMatcher(DictEmbedder(), InMemoryStore())
    app.dependency_overrides[get_talent_matcher_factory] = lambda: lambda: matcher
    try:
        response = TestClient(app).post(
            "/internal/matching/search",
            json={"statements": [{"statement_id": 1, "body": "문장"}]},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["success"] is True
    assert response.json()["data"] == {"matches": [], "collection_criteria": 0}
    paths = set(app.openapi()["paths"])
    assert "/internal/matching/postings/index" in paths
    assert "/internal/matching/search" in paths


def test_job_matching_eval_fixture_has_30_valid_unique_postings() -> None:
    fixture_path = Path(__file__).parents[1] / "evals" / "job_matching_cases.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))

    request, posting_key_of_criterion = fixture_to_index_request(fixture)
    expected_keys = {case["expected_posting_key"] for case in fixture["cases"]}

    assert len(request.postings) == 30
    assert len(fixture["cases"]) == 30
    assert expected_keys == set(posting_key_of_criterion.values())
    assert all(key.startswith("EVAL_FIXTURE:") for key in expected_keys)


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
    assert set(index["ai_to_spring"]) == set(JobPostingIndexResult.model_fields)
    prune = contract["prune"]
    JobPostingPruneRequest.model_validate(prune["spring_to_ai"])
    JobPostingPruneResult.model_validate(prune["ai_to_spring"])
    MatchingRequest.model_validate(search["spring_to_ai"])
    MatchingResponse.model_validate(search["ai_to_spring"])


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


def stored_matcher(embedder) -> TalentMatcher:
    """검색 경로가 임베딩까지 가도록 조건 하나가 이미 색인된 검색기."""
    store = InMemoryStore()
    store.replace_posting(1, [10], [[1.0, 0.0]])
    return TalentMatcher(embedder, store)


def post_matching(path: str, payload: dict, matcher: TalentMatcher | None = None):
    if matcher is not None:
        app.dependency_overrides[get_talent_matcher_factory] = lambda: lambda: matcher
    try:
        return TestClient(app).post(path, json=payload)
    finally:
        app.dependency_overrides.clear()


INDEX_PATH = "/internal/matching/postings/index"
PRUNE_PATH = "/internal/matching/postings/prune"
SEARCH_PATH = "/internal/matching/search"


def index_payload() -> dict:
    return {"postings": [posting(1, [criterion(10)])]}


def search_payload() -> dict:
    return {"statements": [{"statement_id": 1, "body": "문장"}]}


@pytest.mark.parametrize(
    ("path", "payload"),
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, search_payload())],
)
def test_embedding_runs_outside_the_event_loop(path, payload) -> None:
    embedder = ThreadRecordingEmbedder()

    response = post_matching(path, payload, stored_matcher(embedder))

    assert response.status_code == 200
    assert embedder.thread_names
    assert all(name.startswith("matching-index") for name in embedder.thread_names)


@pytest.mark.parametrize(
    ("path", "payload"),
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, search_payload())],
)
@pytest.mark.parametrize("retryable", [True, False])
def test_embedding_failure_uses_error_envelope_with_its_retryable(path, payload, retryable) -> None:
    failure = EmbeddingError("embedding down", retryable=retryable)

    response = post_matching(path, payload, stored_matcher(RaisingEmbedder(failure)))

    assert response.status_code == 503
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == "LLM_UNAVAILABLE"
    assert envelope.error.retryable is retryable


@pytest.mark.parametrize(
    ("path", "payload"),
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, search_payload())],
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
    [(INDEX_PATH, index_payload()), (SEARCH_PATH, search_payload())],
)
def test_unexpected_failure_returns_internal_error_without_leaking_detail(path, payload) -> None:
    failure = RuntimeError("secret internal detail")

    response = post_matching(path, payload, stored_matcher(RaisingEmbedder(failure)))

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
