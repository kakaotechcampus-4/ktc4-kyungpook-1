"""공고 조건 추출 내부 API의 오류 봉투와 실행 위치 테스트."""

from __future__ import annotations

import threading

import pytest
from fastapi.testclient import TestClient

from api.job_postings import get_job_posting_extractor
from main import app
from schemas.common import ErrorEnvelope
from schemas.matching import JobPostingDetailExtractionResult
from services.llm_settings import LLMConfigError
from services.llm_structured_client import (
    LLMContentFilterError,
    LLMInvalidOutputError,
    LLMNonRetryableError,
    LLMStructuredCallError,
    LLMStructuredOutputTooLongError,
)

REQUEST = {
    "source": "COMPANY_CAREER_PAGE",
    "external_posting_id": "backend-101",
    "job_title": "백엔드 개발자",
    "source_url": "https://careers.example.com/jobs/backend-101",
    "raw_text": "자격 요건\nJava 개발 경험이 필요합니다.",
}


class RaisingExtractor:
    def __init__(self, failure: Exception) -> None:
        self._failure = failure

    def extract(self, request):
        raise self._failure


class ThreadRecordingExtractor:
    def __init__(self) -> None:
        self.thread_name = ""

    def extract(self, request):
        self.thread_name = threading.current_thread().name
        return JobPostingDetailExtractionResult(
            source=request.source,
            external_posting_id=request.external_posting_id,
            source_url=request.source_url,
            content_hash="0" * 64,
            outcome="UPDATED",
            content_status="METADATA_ONLY",
            criteria=[],
        )


def post_extract(extractor=None):
    if extractor is not None:
        app.dependency_overrides[get_job_posting_extractor] = lambda: extractor
    try:
        return TestClient(app).post("/internal/job-postings/extract", json=REQUEST)
    finally:
        app.dependency_overrides.clear()


def test_extraction_runs_outside_the_event_loop() -> None:
    extractor = ThreadRecordingExtractor()

    response = post_extract(extractor)

    assert response.status_code == 200
    assert extractor.thread_name.startswith("llm-posting")


@pytest.mark.parametrize(
    ("failure", "retryable"),
    [
        (LLMConfigError("env missing"), False),
        (LLMNonRetryableError("bad key"), False),
        (LLMContentFilterError("filtered"), False),
        (LLMStructuredOutputTooLongError("too long"), False),
        (LLMStructuredCallError("upstream down"), True),
        (LLMInvalidOutputError("bad schema"), True),
    ],
)
def test_llm_failures_use_error_envelope_with_retryable_by_cause(failure, retryable) -> None:
    response = post_extract(RaisingExtractor(failure))

    assert response.status_code == 503
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == "LLM_UNAVAILABLE"
    assert envelope.error.retryable is retryable


def test_missing_llm_settings_return_envelope_instead_of_bare_500(monkeypatch) -> None:
    monkeypatch.delenv("GITORY_LLM_BASE_URL", raising=False)
    monkeypatch.delenv("GITORY_LLM_API_KEY", raising=False)

    response = post_extract()

    assert response.status_code == 503
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == "LLM_UNAVAILABLE"
    assert envelope.error.retryable is False


def test_unexpected_failure_returns_internal_error_without_leaking_detail() -> None:
    response = post_extract(RaisingExtractor(RuntimeError("secret internal detail")))

    assert response.status_code == 500
    envelope = ErrorEnvelope.model_validate(response.json())
    assert envelope.error.code == "INTERNAL_ERROR"
    assert envelope.error.retryable is False
    assert "secret internal detail" not in response.text
