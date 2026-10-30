"""일반 조건 분포 집계 테스트."""

from schemas.matching import JobPostingDetailExtractionResult
from scripts.count_general_criteria import count_general_criteria


def posting(criteria: list[dict]) -> JobPostingDetailExtractionResult:
    return JobPostingDetailExtractionResult.model_validate(
        {
            "source": "TEST",
            "external_posting_id": str(len(criteria)),
            "source_url": "https://example.com/jobs/1",
            "content_hash": "a" * 64,
            "outcome": "UPDATED",
            "content_status": "READY",
            "criteria": criteria,
            "job_category": "BACKEND",
            "experience": {"level": "UNSPECIFIED"},
        }
    )


def test_counts_general_condition_types_and_postings_without_technical_required() -> None:
    report = count_general_criteria(
        [
            posting(
                [
                    {
                        "criterion_type": "REQUIRED",
                        "general_type": "EDUCATION",
                        "text": "학사 졸업",
                        "source_quote": "학사 졸업",
                    },
                    {
                        "criterion_type": "REQUIRED",
                        "general_type": "MILITARY",
                        "text": "병역필",
                        "source_quote": "병역필",
                    },
                ]
            ),
            posting(
                [
                    {
                        "criterion_type": "REQUIRED",
                        "general_type": None,
                        "text": "Python 경험",
                        "source_quote": "Python 경험",
                    },
                    {
                        "criterion_type": "PREFERRED",
                        "general_type": "CERTIFICATE",
                        "text": "정보처리기사",
                        "source_quote": "정보처리기사",
                    },
                ]
            ),
            posting([]),
        ]
    )

    assert report == {
        "postings_with_criteria": 2,
        "general_criteria": {
            "EDUCATION": 1,
            "MILITARY": 1,
            "LANGUAGE": 0,
            "CERTIFICATE": 1,
            "LOCATION": 0,
            "OTHER": 0,
        },
        "general_criteria_total": 3,
        "general_criteria_ratio": 0.75,
        "postings_without_technical_required": 1,
    }
