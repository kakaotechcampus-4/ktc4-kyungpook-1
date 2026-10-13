"""공고 상세 본문 조건 추출·근거 검증 테스트."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from api.job_postings import get_job_posting_extractor
from main import app
from schemas.matching import (
    JobCriterion,
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
)
from services.job_posting_extractor import (
    JobPostingDetailExtractor,
    JobPostingEvidenceMismatch,
)


RAW_TEXT = """담당 업무
Spring Boot 기반 REST API를 설계하고 개발합니다.
자격 요건
Java 개발 경험이 필요합니다.
우대 사항
대용량 트래픽 처리 경험이 있으신 분을 우대합니다.
"""


class FakeExtractionModel:
    def __init__(self, criteria: list[JobCriterion]) -> None:
        self.criteria = criteria
        self.calls = 0

    def extract(self, request):
        self.calls += 1
        return self.criteria


def request_payload(**overrides) -> dict:
    payload = {
        "source": "COMPANY_CAREER_PAGE",
        "external_posting_id": "backend-101",
        "job_title": "백엔드 개발자",
        "source_url": "https://careers.example.com/jobs/backend-101",
        "raw_text": RAW_TEXT,
    }
    payload.update(overrides)
    return payload


def valid_criteria() -> list[JobCriterion]:
    return [
        JobCriterion(
            criterion_type="RESPONSIBILITY",
            text="Spring Boot 기반 REST API 개발",
            source_quote="Spring Boot 기반 REST API를 설계하고 개발합니다.",
        ),
        JobCriterion(
            criterion_type="REQUIRED",
            text="Java 개발 경험",
            source_quote="Java 개발 경험이 필요합니다.",
        ),
        JobCriterion(
            criterion_type="PREFERRED",
            text="대용량 트래픽 처리 경험",
            source_quote="대용량 트래픽 처리 경험이 있으신 분을 우대합니다.",
        ),
    ]


def test_extracts_only_criteria_with_exact_source_quotes() -> None:
    model = FakeExtractionModel(valid_criteria())
    extractor = JobPostingDetailExtractor(model)

    result = extractor.extract(
        JobPostingDetailExtractionRequest.model_validate(request_payload())
    )

    assert result.outcome == "UPDATED"
    assert result.content_status == "READY"
    assert len(result.criteria) == 3
    assert result.criteria[1].source_quote in RAW_TEXT
    assert model.calls == 1


def test_rejects_quote_that_does_not_exist_in_original_body() -> None:
    model = FakeExtractionModel(
        [
            JobCriterion(
                criterion_type="REQUIRED",
                text="AWS 자격증",
                source_quote="AWS 자격증 보유가 필수입니다.",
            )
        ]
    )
    extractor = JobPostingDetailExtractor(model)

    with pytest.raises(JobPostingEvidenceMismatch):
        extractor.extract(
            JobPostingDetailExtractionRequest.model_validate(request_payload())
        )


def test_same_content_hash_skips_llm_call() -> None:
    model = FakeExtractionModel(valid_criteria())
    extractor = JobPostingDetailExtractor(model)
    content_hash = hashlib.sha256(RAW_TEXT.strip().encode("utf-8")).hexdigest()

    result = extractor.extract(
        JobPostingDetailExtractionRequest.model_validate(
            request_payload(previous_content_hash=content_hash)
        )
    )

    assert result.outcome == "UNCHANGED"
    assert result.criteria == []
    assert model.calls == 0


def test_no_explicit_condition_marks_posting_metadata_only() -> None:
    extractor = JobPostingDetailExtractor(FakeExtractionModel([]))

    result = extractor.extract(
        JobPostingDetailExtractionRequest.model_validate(request_payload())
    )

    assert result.content_status == "METADATA_ONLY"


def test_duplicate_criteria_are_removed_after_quote_validation() -> None:
    criterion = valid_criteria()[0]
    extractor = JobPostingDetailExtractor(FakeExtractionModel([criterion, criterion]))

    result = extractor.extract(
        JobPostingDetailExtractionRequest.model_validate(request_payload())
    )

    assert result.criteria == [criterion]


def test_detail_extraction_api_reports_model_usage() -> None:
    extractor = JobPostingDetailExtractor(FakeExtractionModel(valid_criteria()))
    app.dependency_overrides[get_job_posting_extractor] = lambda: extractor
    try:
        response = TestClient(app).post(
            "/internal/job-postings/extract",
            json=request_payload(),
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["data"]["content_status"] == "READY"
    assert response.json()["meta"]["model"] == "gpt-5.6-luna"
    assert response.json()["meta"]["tool_calls_made"] == 1


def test_detail_extraction_api_maps_fabricated_quote_to_evidence_error() -> None:
    extractor = JobPostingDetailExtractor(
        FakeExtractionModel(
            [
                JobCriterion(
                    criterion_type="SKILL",
                    text="Kubernetes",
                    source_quote="Kubernetes 필수",
                )
            ]
        )
    )
    app.dependency_overrides[get_job_posting_extractor] = lambda: extractor
    try:
        response = TestClient(app).post(
            "/internal/job-postings/extract",
            json=request_payload(),
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "EVIDENCE_MISMATCH"


def test_job_posting_detail_contract_examples_validate_against_ai_dtos() -> None:
    contract_path = (
        Path(__file__).parents[2]
        / "contracts"
        / "matching"
        / "job-posting-detail.json"
    )
    contract = json.loads(contract_path.read_text(encoding="utf-8"))

    request = JobPostingDetailExtractionRequest.model_validate(
        contract["spring_to_ai"]["request"]
    )
    result = JobPostingDetailExtractionResult.model_validate(
        contract["ai_to_spring"]["data"]
    )

    assert hashlib.sha256(request.raw_text.strip().encode("utf-8")).hexdigest() == result.content_hash
    assert all(item.source_quote in request.raw_text for item in result.criteria)
