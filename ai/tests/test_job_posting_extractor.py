"""공고 상세 본문 조건 추출·근거 검증 테스트."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from api.job_postings import get_job_posting_extractor
from main import app
from schemas.matching import (
    JobCriterion,
    JobExperience,
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
)
from services.job_posting_extractor import (
    ExtractedExperience,
    ExtractedPosting,
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

UNCHANGED_RESULT = {
    "source": "COMPANY_CAREER_PAGE",
    "external_posting_id": "backend-101",
    "source_url": "https://careers.example.com/jobs/backend-101",
    "content_hash": "a" * 64,
    "outcome": "UNCHANGED",
    "content_status": "READY",
}


def experience(level="UNSPECIFIED", min_years=None, source_quote=None) -> ExtractedExperience:
    return ExtractedExperience(level=level, min_years=min_years, source_quote=source_quote)


class FakeExtractionModel:
    def __init__(
        self,
        criteria: list[JobCriterion],
        extracted_experience: ExtractedExperience | None = None,
        job_category: str = "BACKEND",
    ) -> None:
        self.criteria = criteria
        self.experience = extracted_experience or experience()
        self.job_category = job_category
        self.calls = 0

    def extract(self, request):
        self.calls += 1
        return ExtractedPosting(
            criteria=self.criteria, experience=self.experience, job_category=self.job_category
        )


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
        criterion_type="RESPONSIBILITY", text="Java", source_quote="Java 개발 경험이 필요합니다."
    )

    kept = JobPostingDetailExtractor(FakeExtractionModel([preferred, other])).extract(
        JobPostingDetailExtractionRequest.model_validate(
            request_payload(raw_text=quote + " " + RAW_TEXT)
        )
    )

    assert [c.criterion_type for c in kept.criteria] == ["RESPONSIBILITY"]
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
    assert result.experience is None
    assert result.job_category is None
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
    first = JobCriterion(criterion_type="PREFERRED", text="AWS 경험", source_quote=quote)
    second = JobCriterion(criterion_type="PREFERRED", text="Docker 경험", source_quote=quote)

    required = JobCriterion(criterion_type="REQUIRED", text="Python", source_quote=quote)

    result = extract_with([first, second, required])

    assert [(c.criterion_type, c.text) for c in result.criteria] == [
        ("PREFERRED", quote),
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
    assert result.experience.source_quote in request.raw_text

    unchanged = JobPostingDetailExtractionResult.model_validate(
        contract["ai_to_spring_unchanged"]["data"]
    )
    assert unchanged.outcome == "UNCHANGED"
    assert unchanged.content_hash == result.content_hash
    assert contract["ai_to_spring_unchanged"]["request_previous_content_hash"] == result.content_hash
    assert (unchanged.job_category, unchanged.experience) == (None, None)
    assert contract["ai_to_spring_unchanged"]["data"]["job_category"] is None
    assert contract["ai_to_spring_unchanged"]["data"]["experience"] is None


def test_unknown_criterion_type_skill_is_rejected_by_model_output_schema() -> None:
    payload = {
        "criteria": [
            {"criterion_type": "SKILL", "text": "Java", "source_quote": "Java 개발 경험이 필요합니다."}
        ],
        "experience": {"level": "UNSPECIFIED"},
        "job_category": "BACKEND",
    }

    with pytest.raises(ValidationError):
        ExtractedPosting.model_validate(payload)


def test_unknown_job_category_is_rejected_by_model_output_schema() -> None:
    with pytest.raises(ValidationError):
        ExtractedPosting.model_validate(
            {"criteria": [], "experience": {"level": "UNSPECIFIED"}, "job_category": "SALES"}
        )


def test_posting_with_entry_expression_is_entry_without_years() -> None:
    quote = "신입 지원 가능"
    model = FakeExtractionModel(
        valid_criteria(), experience("ENTRY", 0, quote), job_category="DATA"
    )

    result = JobPostingDetailExtractor(model).extract(
        JobPostingDetailExtractionRequest.model_validate(
            request_payload(raw_text=RAW_TEXT + quote)
        )
    )

    assert (result.experience.level, result.experience.min_years) == ("ENTRY", None)
    assert result.experience.source_quote == quote
    assert result.job_category == "DATA"


def test_experienced_posting_keeps_min_years_and_quote() -> None:
    quote = "경력 3년 이상"
    model = FakeExtractionModel(valid_criteria(), experience("EXPERIENCED", 3, quote))

    result = JobPostingDetailExtractor(model).extract(
        JobPostingDetailExtractionRequest.model_validate(
            request_payload(raw_text=RAW_TEXT + quote)
        )
    )

    assert (result.experience.level, result.experience.min_years) == ("EXPERIENCED", 3)


def test_posting_without_experience_is_unspecified_without_years_or_quote() -> None:
    result = extract_with(valid_criteria())

    assert result.experience.level == "UNSPECIFIED"
    assert result.experience.min_years is None
    assert result.experience.source_quote is None


def extract_experience(extracted: ExtractedExperience, body_addition: str = ""):
    model = FakeExtractionModel(valid_criteria(), extracted)
    return JobPostingDetailExtractor(model).extract(
        JobPostingDetailExtractionRequest.model_validate(
            request_payload(raw_text=RAW_TEXT + body_addition)
        )
    )


UNTRUSTED_EXPERIENCES = {
    "인용이 원문에 없음": ("EXPERIENCED", 5, "경력 5년 이상", ""),
    "ENTRY 인용이 원문에 없음": ("ENTRY", None, "신입 지원 가능", ""),
    "인용 없음": ("EXPERIENCED", 3, None, ""),
    "인용이 공백뿐": ("EXPERIENCED", 3, "   ", ""),
    "인용이 원문과 공백만 다름": ("EXPERIENCED", 3, "경력  3년 이상", "경력 3년 이상"),
    "인용이 1000자 초과": ("EXPERIENCED", 3, "경력 3년 이상 " + "가" * 1000, "경력 3년 이상 " + "가" * 1000),
    "EXPERIENCED 연차 없음": ("EXPERIENCED", None, "경력 3년 이상", "경력 3년 이상"),
    "연차 61": ("EXPERIENCED", 61, "경력 61년 이상", "경력 61년 이상"),
    "연차가 인용에 없음": ("EXPERIENCED", 5, "경력 3년 이상", "경력 3년 이상"),
    "연차 0 이고 인용에 0 이 없음": ("EXPERIENCED", 0, "경력 무관", "경력 무관"),
}

UPPER_BOUND_ONLY_EXPERIENCES = {
    "미만": ("EXPERIENCED", 1, "경력 1년 미만"),
    "이하": ("EXPERIENCED", 3, "경력 3년 이하"),
    "up to": ("EXPERIENCED", 2, "up to 2 years of experience"),
    "연차 없음": ("EXPERIENCED", None, "경력 1년 미만"),
}


@pytest.mark.parametrize(
    ("level", "min_years", "quote", "body_addition"),
    UNTRUSTED_EXPERIENCES.values(),
    ids=UNTRUSTED_EXPERIENCES.keys(),
)
def test_untrusted_experience_degrades_to_unspecified_with_one_id_only_warning(
    level, min_years, quote, body_addition, caplog
) -> None:
    with caplog.at_level("WARNING"):
        result = extract_experience(experience(level, min_years, quote), body_addition)

    assert result.experience == JobExperience(level="UNSPECIFIED")
    assert len(result.criteria) == 3
    assert [record.getMessage() for record in caplog.records] == [
        "공고 경력 정보 제외: posting=backend-101"
    ]


@pytest.mark.parametrize(
    ("level", "min_years", "quote"),
    UPPER_BOUND_ONLY_EXPERIENCES.values(),
    ids=UPPER_BOUND_ONLY_EXPERIENCES.keys(),
)
def test_upper_bound_only_experience_is_entry_without_years(level, min_years, quote) -> None:
    result = extract_experience(experience(level, min_years, quote), quote)

    assert (result.experience.level, result.experience.min_years) == ("ENTRY", None)


def test_experience_with_both_bounds_keeps_the_lower_bound() -> None:
    quote = "경력 3년 이상 5년 이하"

    result = extract_experience(experience("EXPERIENCED", 3, quote), quote)

    assert (result.experience.level, result.experience.min_years) == ("EXPERIENCED", 3)


def test_experience_quote_is_trimmed_before_it_is_found_in_the_body() -> None:
    result = extract_experience(experience("EXPERIENCED", 3, "  경력 3년 이상\n"), "경력 3년 이상")

    assert (result.experience.level, result.experience.source_quote) == ("EXPERIENCED", "경력 3년 이상")


def test_zero_years_with_zero_in_the_quote_is_entry_without_years() -> None:
    quote = "경력 0년 이상"

    result = extract_experience(experience("EXPERIENCED", 0, quote), quote)

    assert (result.experience.level, result.experience.min_years) == ("ENTRY", None)
    assert result.experience.source_quote == quote


def test_unspecified_from_the_model_is_not_logged_as_excluded(caplog) -> None:
    with caplog.at_level("WARNING"):
        result = extract_experience(experience())

    assert result.experience == JobExperience(level="UNSPECIFIED")
    assert caplog.records == []


@pytest.mark.parametrize(
    "fields",
    [
        {"level": "UNSPECIFIED", "min_years": 3},
        {"level": "UNSPECIFIED", "source_quote": "경력 3년 이상"},
        {"level": "ENTRY"},
        {"level": "ENTRY", "min_years": 0, "source_quote": "신입 지원 가능"},
        {"level": "ENTRY", "min_years": 2, "source_quote": "신입 지원 가능"},
        {"level": "EXPERIENCED", "source_quote": "경력 3년 이상"},
        {"level": "EXPERIENCED", "min_years": 0, "source_quote": "경력 0년 이상"},
        {"level": "EXPERIENCED", "min_years": 3},
        {"level": "EXPERIENCED", "min_years": 61, "source_quote": "경력 61년 이상"},
    ],
)
def test_contract_experience_model_rejects_inconsistent_fields(fields) -> None:
    with pytest.raises(ValidationError):
        JobExperience.model_validate(fields)


def test_contract_experience_model_accepts_the_largest_years() -> None:
    assert JobExperience(level="EXPERIENCED", min_years=60, source_quote="경력 60년 이상").min_years == 60


@pytest.mark.parametrize(
    "present",
    [{}, {"job_category": "BACKEND"}, {"experience": {"level": "UNSPECIFIED"}}],
    ids=["둘 다 없음", "경력 없음", "분류 없음"],
)
def test_updated_result_requires_category_and_experience(present) -> None:
    with pytest.raises(ValidationError):
        JobPostingDetailExtractionResult.model_validate(
            {**UNCHANGED_RESULT, "outcome": "UPDATED", "content_hash": "a" * 64, **present}
        )


def test_result_model_rejects_an_unknown_job_category() -> None:
    with pytest.raises(ValidationError):
        JobPostingDetailExtractionResult.model_validate(
            {
                **UNCHANGED_RESULT,
                "outcome": "UPDATED",
                "experience": {"level": "UNSPECIFIED"},
                "job_category": "SALES",
            }
        )


def test_unchanged_result_cannot_carry_conditions() -> None:
    with pytest.raises(ValidationError):
        JobPostingDetailExtractionResult.model_validate(
            {**UNCHANGED_RESULT, "job_category": "BACKEND"}
        )
