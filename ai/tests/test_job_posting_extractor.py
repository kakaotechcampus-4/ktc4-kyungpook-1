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


def test_required_quote_missing_from_original_body_rejects_whole_posting() -> None:
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


def test_non_required_quote_missing_from_body_drops_only_that_condition(caplog) -> None:
    # 인용이 원문에 없고 text 도 그 인용에서 나오지 않는 경우(둘 다 지어냄)다.
    fabricated = JobCriterion(
        criterion_type="PREFERRED",
        text="Kubernetes 운영 경험",
        source_quote="AWS 자격증 보유자를 우대합니다.",
    )

    with caplog.at_level("WARNING"):
        result = extract_with([*valid_criteria(), fabricated])

    assert result.criteria == valid_criteria()
    assert "count=1" in caplog.text
    assert "PREFERRED" in caplog.text


def extract_with(criteria: list[JobCriterion]):
    extractor = JobPostingDetailExtractor(FakeExtractionModel(criteria))
    return extractor.extract(
        JobPostingDetailExtractionRequest.model_validate(request_payload())
    )


def test_text_outside_its_quote_is_replaced_with_the_quote(caplog) -> None:
    # 인용은 원문에 있지만 text 는 인용에 없는 자격증을 말한다.
    fabricated = JobCriterion(
        criterion_type="REQUIRED",
        text="AWS 자격증 필수",
        source_quote="Java 개발 경험이 필요합니다.",
    )

    with caplog.at_level("WARNING"):
        result = extract_with([fabricated])

    assert [c.text for c in result.criteria] == ["Java 개발 경험이 필요합니다."]
    assert result.criteria[0].source_quote == fabricated.source_quote
    assert "count=1" in caplog.text


def test_short_quote_is_accepted() -> None:
    criterion = JobCriterion(
        criterion_type="REQUIRED", text="Java", source_quote="Java"
    )

    assert extract_with([criterion]).criteria == [criterion]


def run_with_body(criterion: JobCriterion, body: str):
    return JobPostingDetailExtractor(FakeExtractionModel([criterion])).extract(
        JobPostingDetailExtractionRequest.model_validate(request_payload(raw_text=body))
    )


def test_quote_of_exactly_the_text_limit_replaces_text() -> None:
    quote = "가" * 1000
    criterion = JobCriterion(criterion_type="PREFERRED", text="AWS", source_quote=quote)

    result = run_with_body(criterion, quote)

    assert [c.text for c in result.criteria] == [quote]


def test_quote_longer_than_the_text_limit_is_treated_like_missing_evidence() -> None:
    quote = "가" * 1001
    preferred = JobCriterion(criterion_type="PREFERRED", text="AWS", source_quote=quote)
    required = JobCriterion(criterion_type="REQUIRED", text="AWS", source_quote=quote)
    other = JobCriterion(
        criterion_type="SKILL", text="Java", source_quote="Java 개발 경험이 필요합니다."
    )

    kept = JobPostingDetailExtractor(FakeExtractionModel([preferred, other])).extract(
        JobPostingDetailExtractionRequest.model_validate(
            request_payload(raw_text=quote + " " + RAW_TEXT)
        )
    )

    assert [c.criterion_type for c in kept.criteria] == ["SKILL"]
    with pytest.raises(JobPostingEvidenceMismatch):
        run_with_body(required, quote)


def test_all_conditions_dropped_rejects_posting_so_hash_is_not_stored() -> None:
    invented = JobCriterion(
        criterion_type="PREFERRED",
        text="AWS 자격증",
        source_quote="AWS 자격증 보유자를 우대합니다.",
    )

    with pytest.raises(JobPostingEvidenceMismatch):
        extract_with([invented])


def test_model_returning_no_conditions_at_all_is_metadata_only_not_rejected() -> None:
    result = extract_with([])

    assert result.content_status == "METADATA_ONLY"
    assert result.criteria == []


def test_logs_never_contain_posting_text(caplog) -> None:
    secret = "비밀스러운 문장"
    fabricated = [
        JobCriterion(criterion_type="PREFERRED", text=secret, source_quote=secret),
        JobCriterion(
            criterion_type="RESPONSIBILITY",
            text=secret,
            source_quote="Java 개발 경험이 필요합니다.",
        ),
    ]

    with caplog.at_level("WARNING"):
        extract_with(fabricated)

    assert caplog.text
    assert secret not in caplog.text
    assert "Java 개발" not in caplog.text


def test_condition_text_is_compared_ignoring_case_spacing_and_particles() -> None:
    criterion = JobCriterion(
        criterion_type="RESPONSIBILITY",
        text="spring   boot 기반 rest api 개발",
        source_quote="Spring Boot 기반 REST API를 설계하고 개발합니다.",
    )

    assert extract_with([criterion]).criteria == [criterion]


def test_punctuation_around_condition_text_words_is_ignored() -> None:
    criterion = JobCriterion(
        criterion_type="REQUIRED",
        text="Spring Boot, JPA 활용 경험",
        source_quote="Spring Boot와 JPA를 활용한 경험이 필요합니다.",
    )
    body = request_payload(raw_text="Spring Boot와 JPA를 활용한 경험이 필요합니다.")

    result = JobPostingDetailExtractor(FakeExtractionModel([criterion])).extract(
        JobPostingDetailExtractionRequest.model_validate(body)
    )

    assert result.criteria == [criterion]


def test_english_word_inside_a_longer_word_does_not_count_as_in_the_quote() -> None:
    criterion = JobCriterion(
        criterion_type="REQUIRED",
        text="Java 개발 경험",
        source_quote="JavaScript 개발 경험이 필요합니다.",
    )
    body = request_payload(raw_text="JavaScript 개발 경험이 필요합니다.")

    result = JobPostingDetailExtractor(FakeExtractionModel([criterion])).extract(
        JobPostingDetailExtractionRequest.model_validate(body)
    )

    assert [c.text for c in result.criteria] == ["JavaScript 개발 경험이 필요합니다."]


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


def test_texts_that_become_equal_after_replacement_are_deduplicated() -> None:
    quote = "Java 개발 경험이 필요합니다."
    first = JobCriterion(criterion_type="SKILL", text="AWS 경험", source_quote=quote)
    second = JobCriterion(criterion_type="SKILL", text="Docker 경험", source_quote=quote)

    required = JobCriterion(criterion_type="REQUIRED", text="Python", source_quote=quote)

    result = extract_with([first, second, required])

    assert [(c.criterion_type, c.text) for c in result.criteria] == [
        ("SKILL", quote),
        ("REQUIRED", quote),
    ]


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


def test_detail_extraction_api_reports_the_configured_model(monkeypatch) -> None:
    monkeypatch.setenv("GITORY_JOB_POSTING_MODEL", "other-model")
    extractor = JobPostingDetailExtractor(FakeExtractionModel(valid_criteria()))
    app.dependency_overrides[get_job_posting_extractor] = lambda: extractor
    try:
        response = TestClient(app).post(
            "/internal/job-postings/extract",
            json=request_payload(),
        )
    finally:
        app.dependency_overrides.clear()

    assert response.json()["meta"]["model"] == "other-model"


def test_detail_extraction_api_maps_fabricated_required_quote_to_evidence_error(caplog) -> None:
    extractor = JobPostingDetailExtractor(
        FakeExtractionModel(
            [
                JobCriterion(
                    criterion_type="REQUIRED",
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
    assert response.json()["error"]["retryable"] is False
    assert all(record.exc_info is None for record in caplog.records)
    assert any("posting=backend-101" in record.getMessage() for record in caplog.records)


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
