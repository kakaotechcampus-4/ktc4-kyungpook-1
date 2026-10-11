"""공고 상세 본문에서 근거 인용이 있는 직무 조건만 추출한다."""

from __future__ import annotations

import hashlib
import os
from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field

from schemas.matching import (
    ExtractionOutcome,
    JobCriterion,
    JobPostingContentStatus,
    JobPostingDetailExtractionRequest,
    JobPostingDetailExtractionResult,
)
from services.llm_structured_client import (
    LLMSettings,
    LLMStructuredCallError,
    StructuredLLMClient,
)


SYSTEM_PROMPT = """당신은 채용공고 상세 본문을 구조화하는 추출기다.
입력 본문에 명시된 내용만 사용한다. 추론하거나 일반적인 직무 지식을 추가하지 않는다.
각 조건은 RESPONSIBILITY, REQUIRED, PREFERRED, SKILL 중 하나로 분류한다.
source_quote에는 판단 근거가 된 원문을 글자 하나 바꾸지 말고 그대로 복사한다.
필수인지 우대인지 본문에서 구분할 수 없으면 RESPONSIBILITY 또는 SKILL로 분류한다.
광고 문구, 복지, 회사 소개, 채용 담당자 개인정보는 제외한다.
"""


class _ExtractedJobCriteria(BaseModel):
    model_config = ConfigDict(extra="forbid")

    criteria: list[JobCriterion] = Field(default_factory=list, max_length=100)


class JobPostingExtractionModel(Protocol):
    """실제 Luna 호출과 테스트 대역을 분리하는 모델 포트."""

    def extract(self, request: JobPostingDetailExtractionRequest) -> list[JobCriterion]:
        """상세 본문에서 인용이 있는 직무 조건을 반환한다."""


class LunaJobPostingExtractionModel:
    """팀에서 확정한 Luna 구조화 출력 구현체."""

    def __init__(self) -> None:
        base_url = os.getenv("GITORY_LLM_BASE_URL", "").strip()
        api_key = os.getenv("GITORY_LLM_API_KEY", "").strip()
        if not base_url or not api_key:
            raise RuntimeError("GITORY_LLM_BASE_URL과 GITORY_LLM_API_KEY가 필요합니다.")
        self._client = StructuredLLMClient(
            LLMSettings(
                base_url=base_url,
                api_key=api_key,
                model=os.getenv("GITORY_LLM_MODEL", "gpt-5.6-luna"),
                reasoning_effort="medium",
            )
        )

    def extract(self, request: JobPostingDetailExtractionRequest) -> list[JobCriterion]:
        result = self._client.call(
            system_prompt=SYSTEM_PROMPT,
            payload={
                "job_title": request.job_title,
                "posting_text": request.raw_text,
            },
            output_model=_ExtractedJobCriteria,
            max_completion_tokens=2000,
        )
        return result.output.criteria


class JobPostingEvidenceMismatch(ValueError):
    """모델이 공고 원문에 없는 인용을 반환한 경우."""


class JobPostingDetailExtractor:
    """해시 비교와 원문 인용 검증을 모델 밖에서 강제한다."""

    def __init__(self, model: JobPostingExtractionModel) -> None:
        self._model = model

    def extract(
        self,
        request: JobPostingDetailExtractionRequest,
    ) -> JobPostingDetailExtractionResult:
        raw_text = request.raw_text.strip()
        content_hash = hashlib.sha256(raw_text.encode("utf-8")).hexdigest()
        if request.previous_content_hash == content_hash:
            return JobPostingDetailExtractionResult(
                source=request.source,
                external_posting_id=request.external_posting_id,
                source_url=request.source_url,
                content_hash=content_hash,
                outcome=ExtractionOutcome.UNCHANGED,
                content_status=request.previous_content_status,
                criteria=[],
            )

        criteria = self._validate_and_deduplicate(self._model.extract(request), raw_text)
        return JobPostingDetailExtractionResult(
            source=request.source,
            external_posting_id=request.external_posting_id,
            source_url=request.source_url,
            content_hash=content_hash,
            outcome=ExtractionOutcome.UPDATED,
            content_status=(
                JobPostingContentStatus.READY
                if criteria
                else JobPostingContentStatus.METADATA_ONLY
            ),
            criteria=criteria,
        )

    @staticmethod
    def _validate_and_deduplicate(
        criteria: list[JobCriterion],
        raw_text: str,
    ) -> list[JobCriterion]:
        result: list[JobCriterion] = []
        seen: set[tuple[str, str]] = set()
        for criterion in criteria:
            if criterion.source_quote not in raw_text:
                raise JobPostingEvidenceMismatch(
                    "추출 조건의 source_quote가 공고 원문에 없습니다."
                )
            key = (criterion.criterion_type.value, criterion.text.casefold())
            if key not in seen:
                seen.add(key)
                result.append(criterion)
        return result


__all__ = [
    "JobPostingDetailExtractor",
    "JobPostingEvidenceMismatch",
    "JobPostingExtractionModel",
    "LLMStructuredCallError",
    "LunaJobPostingExtractionModel",
]
