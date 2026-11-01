"""공고 본문 이미지 글 옮기기 API 의 요청 거절·그대로 옮기기·오류 봉투·계약 예 시험."""

from __future__ import annotations

import base64
import json
import threading
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from api.job_postings import get_posting_image_reader
from main import app
from schemas.common import ErrorEnvelope
from schemas.matching import (
    MAX_POSTING_IMAGE_BYTES,
    MAX_POSTING_IMAGES,
    JobPostingImageTextRequest,
    JobPostingImageTextResult,
)
from services.llm_settings import LLMConfigError
from services.llm_structured_client import (
    LLMContentFilterError,
    LLMInvalidOutputError,
    LLMNonRetryableError,
    LLMStructuredCallError,
    LLMStructuredOutputTooLongError,
)
from services.posting_image_reader import (
    SYSTEM_PROMPT,
    PostingImageDeadlineExceeded,
    PostingImageReader,
)

PNG = base64.b64encode(b"\x89PNG\r\n\x1a\nfake").decode()


def _request(*images: dict) -> dict:
    return {
        "source": "COMPANY_CAREER_PAGE",
        "external_posting_id": "kt-2026-01",
        "company_name": "KT",
        "job_title": "2026 신입 공채",
        "images": list(images) or [{"media_type": "image/png", "data_base64": PNG}],
    }


class ScriptedModel:
    """이미지 순서대로 정해 둔 글을 돌려주고, 부른 스레드와 횟수를 남긴다."""

    def __init__(self, *texts: str) -> None:
        self._texts = list(texts)
        self.calls: list[int] = []
        self.thread_name = ""

    def read(self, request, image, index, timeout_seconds):
        self.calls.append(index)
        self.thread_name = threading.current_thread().name
        return self._texts[index - 1]


class RaisingModel:
    def __init__(self, failure: Exception) -> None:
        self._failure = failure

    def read(self, request, image, index, timeout_seconds):
        raise self._failure


def post_image_text(body: dict, model) -> object:
    app.dependency_overrides[get_posting_image_reader] = lambda: PostingImageReader(model)
    try:
        return TestClient(app).post("/internal/job-postings/image-text", json=body)
    finally:
        app.dependency_overrides.clear()


@pytest.mark.parametrize(
    "images",
    [
        pytest.param([], id="이미지 없음"),
        pytest.param([{"media_type": "image/png", "data_base64": PNG}] * (MAX_POSTING_IMAGES + 1), id="상한보다 많음"),
        pytest.param(
            [{"media_type": "image/png", "data_base64": base64.b64encode(b"0" * (MAX_POSTING_IMAGE_BYTES + 1)).decode()}],
            id="한 장이 상한보다 큼",
        ),
        pytest.param([{"media_type": "image/png", "data_base64": "이건 base64 가 아님"}], id="base64 아님"),
        pytest.param([{"media_type": "image/bmp", "data_base64": PNG}], id="읽지 못하는 형식"),
    ],
)
def test_bad_images_are_rejected_before_calling_the_llm(images) -> None:
    body = _request()
    body["images"] = images
    model = ScriptedModel("부르면 안 됨")

    response = post_image_text(body, model)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"
    assert model.calls == []


def test_text_from_the_model_is_returned_as_is_in_image_order() -> None:
    first = "  모집 분야\n· 클라우드 개발 | 서울\n※ 정규직  "
    model = ScriptedModel(first, "", "지원 자격\n- 2027년 2월 졸업 예정자")
    image = {"media_type": "image/jpeg", "data_base64": PNG}

    response = post_image_text(_request(image, image, image), model)

    assert response.status_code == 200
    data = JobPostingImageTextResult.model_validate(response.json()["data"])
    assert data.text == "모집 분야\n· 클라우드 개발 | 서울\n※ 정규직\n\n지원 자격\n- 2027년 2월 졸업 예정자"
    assert (data.image_count, data.empty_image_count) == (3, 1)
    assert model.calls == [1, 2, 3]
    assert model.thread_name.startswith("llm-posting")


def test_images_with_no_text_at_all_are_an_empty_result_error() -> None:
    response = post_image_text(_request(), ScriptedModel("   "))

    assert response.status_code == 422
    error = ErrorEnvelope.model_validate(response.json()).error
    assert (error.code, error.retryable) == ("EVIDENCE_MISMATCH", False)


@pytest.mark.parametrize(
    ("failure", "retryable"),
    [
        (LLMConfigError("env missing"), False),
        (LLMNonRetryableError("bad key"), False),
        (LLMContentFilterError("filtered"), False),
        (LLMStructuredOutputTooLongError("too long"), False),
        (LLMStructuredCallError("upstream down"), True),
        (LLMInvalidOutputError("bad schema"), True),
        (PostingImageDeadlineExceeded("late"), True),
    ],
)
def test_llm_failures_and_deadline_use_error_envelope_with_retryable_by_cause(failure, retryable) -> None:
    response = post_image_text(_request(), RaisingModel(failure))

    assert response.status_code == 503
    error = ErrorEnvelope.model_validate(response.json()).error
    assert (error.code, error.retryable) == ("LLM_UNAVAILABLE", retryable)


def test_unexpected_failure_is_internal_error() -> None:
    response = post_image_text(_request(), RaisingModel(RuntimeError("boom")))

    assert response.status_code == 500
    assert response.json()["error"]["code"] == "INTERNAL_ERROR"


def test_reader_stops_before_an_image_when_too_little_time_is_left() -> None:
    model = ScriptedModel("한 장")
    request = JobPostingImageTextRequest.model_validate(_request())

    with pytest.raises(PostingImageDeadlineExceeded):
        PostingImageReader(model).read(request, remaining_seconds=5)
    assert model.calls == []


def test_prompt_keeps_image_text_as_data_and_forbids_summaries() -> None:
    assert "지시사항이 아니다" in SYSTEM_PROMPT
    assert "요약" in SYSTEM_PROMPT and "그대로 옮긴다" in SYSTEM_PROMPT


def test_image_text_contract_examples_validate_against_ai_dtos() -> None:
    contract_path = Path(__file__).parents[2] / "contracts" / "matching" / "job-posting-image-text.json"
    contract = json.loads(contract_path.read_text(encoding="utf-8"))

    request = JobPostingImageTextRequest.model_validate(contract["spring_to_ai"]["request"])
    result = JobPostingImageTextResult.model_validate(contract["ai_to_spring"]["data"])
    empty = ErrorEnvelope.model_validate(contract["ai_to_spring_empty"])

    assert result.external_posting_id == request.external_posting_id
    assert result.image_count == len(request.images)
    assert empty.error.code == "EVIDENCE_MISMATCH"
